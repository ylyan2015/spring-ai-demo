// Phase 5 #1 整集试产验收驱动（本地 ComfyUI 全链路：SD1.5+LoRA 分镜 → Wan i2v → edge-tts → FFmpeg 导出）
// 前置：应用以 --auth.captcha.debug-text=true --video.workers=1 启动；ComfyUI 在 8188 且队列空；DEEPSEEK_API_KEY 已设
// 用法：node tools/e2e-local-episode.js [baseUrl]
// 产物保留在账号下供前端查看；不自动删除。暂存不提交纪律适用。
const crypto = require('crypto');
const http = require('http');
const { execFileSync } = require('child_process');
const BASE = process.argv[2] || 'http://localhost:8080';
let cookie = '';

async function req(method, path, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual' });
    const setCookie = resp.headers.get('set-cookie');
    if (setCookie) cookie = setCookie.split(';')[0];
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* non-JSON */ }
    return { status: resp.status, json, text };
}

// 长任务（单镜头 i2v ~5min、导出分钟级）：raw http 无默认超时，防 undici 300s headers/body 超时
function longPost(path, body) {
    return new Promise((resolve, reject) => {
        const data = JSON.stringify(body || {});
        const u = new URL(BASE + path);
        const r = http.request({ hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Cookie': cookie, 'Content-Length': Buffer.byteLength(data) } },
            resp => {
                let buf = '';
                resp.on('data', c => buf += c);
                resp.on('end', () => { let json = null; try { json = JSON.parse(buf); } catch (e) {} resolve({ status: resp.statusCode, json, text: buf }); });
            });
        r.on('error', reject);
        r.write(data);
        r.end();
    });
}

function rsaEncrypt(publicKeyBase64, plain) {
    const der = Buffer.from(publicKeyBase64, 'base64');
    const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    return crypto.publicEncrypt({ key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
        Buffer.from(plain, 'utf8')).toString('base64');
}

function check(name, ok, detail) {
    console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''));
    if (!ok) process.exitCode = 1;
}
const t0 = Date.now();
const lap = () => ((Date.now() - t0) / 1000).toFixed(0) + 's';

(async () => {
    // 1. 认证
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'trial_' + Date.now() % 100000;
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', { username, password: enc, confirmPassword: enc, captcha: cap.json.captcha });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 剧本（单角色 saki，5 镜头短切）
    const gen = await req('POST', '/api/video/scripts/generate', {
        idea: '放学后的走廊：女孩 saki（茶色头发、校服、红色领结）独自走过黄昏走廊，回头微笑。治愈系动漫短剧，恰好5个镜头，每镜头2秒，单角色 saki'
    });
    const script = gen.json && gen.json.data;
    check('script-generate', gen.status === 200 && script && script.id, 'scriptId=' + (script && script.id) + ' shots=' + ((script && script.shots) || []).length);
    if (!script || !script.id) { console.log(gen.text.slice(0, 500)); return; }
    let shots = script.shots || [];
    // 控 GPU 预算：最多 6 镜头（2s/镜 ≈ 320s GPU/镜）
    for (const extra of shots.slice(6)) {
        await req('DELETE', '/api/video/scripts/' + script.id + '/shots/' + extra.id);
    }
    shots = shots.slice(0, 6);

    // 3. 角色卡挂 LoRA（身份一致性）+ 镜头联动/短切
    const chars = await req('GET', '/api/video/characters?scriptId=' + script.id);
    const charList = (chars.json && chars.json.data) || [];
    check('characters-auto-created', charList.length >= 1, charList.map(c => c.name).join(','));
    if (!charList.length) return;
    const hero = charList[0];
    const cu = await req('PUT', '/api/video/characters/' + hero.id + '?scriptId=' + script.id,
        { loraName: 'saki_chan_lora.safetensors', triggerWord: 'saki_chan' });
    check('character-lora-set', cu.json && cu.json.success && cu.json.data.loraName === 'saki_chan_lora.safetensors',
        'trigger=' + (cu.json && cu.json.data && cu.json.data.triggerWord));
    for (const s of shots) {
        const su = await req('PUT', '/api/video/scripts/' + script.id + '/shots/' + s.id,
            { durationSeconds: 2, characterIds: [hero.id] });
        if (!(su.json && su.json.success)) { check('shot-link ' + s.shotOrder, false, su.json && su.json.message); return; }
    }
    check('shots-linked-2s', true, shots.length + ' shots');

    // 4. 切本地生成源
    const ig = await req('PUT', '/api/video/images/generator?type=comfyui');
    const vg = await req('PUT', '/api/video/videos/generator?type=comfyui');
    check('switch-comfyui', ig.json && ig.json.success && vg.json && vg.json.success);
    if (process.exitCode) return;

    // 5. 分镜图（SD1.5+LoRA，~8-11s/张）
    for (const s of shots) {
        const r = await req('POST', '/api/video/images/shot/' + script.id + '/' + s.id, {});
        const d = r.json && r.json.data;
        check('shot ' + s.shotOrder + ' image', r.status === 200 && r.json.success && d && d.imageStatus === 'READY',
            (d ? d.imageUrl : '') + ' @' + lap());
        if (process.exitCode) return;
    }

    // 6. 图生视频（Wan2.2 本地，2s→49f ≈ 320s/镜，顺序=单卡串行）
    for (const s of shots) {
        const r = await longPost('/api/video/videos/shot/' + script.id + '/' + s.id, {});
        const d = r.json && r.json.data;
        check('shot ' + s.shotOrder + ' video', r.status === 200 && r.json.success && d && d.videoStatus === 'READY',
            (d ? d.videoUrl : '') + ' @' + lap());
        if (process.exitCode) { console.log((r.text || '').slice(0, 300)); return; }
    }

    // 7. 高清导出（720p + 字幕 + BGM calm + edge-tts）
    const bgm = await req('GET', '/api/video/bgm');
    const bgmList = (bgm.json && bgm.json.data) || [];
    const calm = bgmList.find(b => (b.category || '').includes('calm')) || bgmList[0];
    const exp = await longPost('/api/video/export/hd', {
        scriptId: script.id, resolution: '720p', aspectRatio: '16:9',
        bgmId: calm ? calm.id : null, subtitle: true, subtitleLanguage: 'zh'
    });
    const video = exp.json && exp.json.data && exp.json.data.video;
    check('hd-export', exp.status === 200 && exp.json.success && video && video.videoUrl,
        'url=' + (video && video.videoUrl) + ' duration=' + (video && video.totalDuration) + 's @' + lap());
    if (!video || !video.videoUrl) { console.log((exp.text || '').slice(0, 500)); return; }

    // 8. ffprobe 验收（分辨率/时长/音轨）
    const local = video.videoUrl.replace(/^\/images\//, 'uploads/');
    try {
        const probe = execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type,width,height',
            '-show_entries', 'format=duration', '-of', 'json', local], { encoding: 'utf8' });
        const pj = JSON.parse(probe);
        const v = (pj.streams || []).find(x => x.codec_type === 'video');
        const a = (pj.streams || []).find(x => x.codec_type === 'audio');
        check('ffprobe-video', !!v && v.width === 1280 && v.height === 720, v ? v.width + 'x' + v.height : 'none');
        check('ffprobe-audio', !!a, a ? a.codec_type : 'MISSING(TTS/BGM)');
        // 合成时长 = Σ镜头时长 − (n−1)×XFADE_DURATION(0.8)（VideoComposeService 常量；镜头时长取整 2s）
        const expect = shots.length * 2 - (shots.length - 1) * 0.8;
        check('ffprobe-duration', Math.abs(Number(pj.format.duration) - expect) <= 0.5,
            pj.format.duration + 's expect≈' + expect + 's');
        console.log('FINAL | ' + local);
    } catch (e) {
        check('ffprobe', false, e.message);
    }
    console.log('DONE | scriptId=' + script.id + ' user=' + username + '（保留供前端查看） total=' + lap());
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });