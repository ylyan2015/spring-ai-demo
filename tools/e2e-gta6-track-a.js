// Track A（AI 短剧集·本地零云依赖）GTA6《十三年等待》全流程验证驱动
// 前置：应用以 --auth.captcha.debug-text=true --video.workers=1 启动；ComfyUI 在 8188；无云端 key（全本地链路）
// 用法：node tools/e2e-gta6-track-a.js [baseUrl]
// 策略：读 gta6-13-years-script.json（16:9 母版 9 镜）→ POST /scripts 建 storyboard 脚本
//       → 切 comfyui 生图/生视频 → 9 镜 SD1.5 分镜 → shot1(5s)+shot7(6s) Wan2.2 i2v（控 GPU 预算）
//       → 导出 16:9 720p（字幕+BGM calm+TTS+AI标识）→ 产物保留供前端查看，暂存不提交
const crypto = require('crypto');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const BASE = process.argv[2] || 'http://localhost:8080';
const SCRIPT_JSON = path.join(__dirname, '..', 'docs', 'story', 'gta6', 'gta6-13-years-script.json');
const I2V_ORDERS = [1, 7]; // 运动 showcase：钩子(微推5s) + 霓虹致敬(缓慢平移6s)，余镜静图运镜
let cookie = '';

async function req(method, p, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual' });
    const sc = resp.headers.get('set-cookie');
    if (sc) cookie = sc.split(';')[0];
    const text = await resp.text();
    let json = null; try { json = JSON.parse(text); } catch (e) {}
    return { status: resp.status, json, text };
}
// 长任务（i2v 分钟级、导出）：raw http 无默认超时
function longPost(p, body) {
    return new Promise((resolve, reject) => {
        const data = JSON.stringify(body || {});
        const u = new URL(BASE + p);
        const r = http.request({ hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Cookie': cookie, 'Content-Length': Buffer.byteLength(data) } },
            resp => { let buf = ''; resp.on('data', c => buf += c);
                resp.on('end', () => { let json = null; try { json = JSON.parse(buf); } catch (e) {} resolve({ status: resp.statusCode, json, text: buf }); }); });
        r.on('error', reject); r.write(data); r.end();
    });
}
function rsaEncrypt(pub, plain) {
    const key = crypto.createPublicKey({ key: Buffer.from(pub, 'base64'), format: 'der', type: 'spki' });
    return crypto.publicEncrypt({ key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(plain, 'utf8')).toString('base64');
}
function check(name, ok, detail) { console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : '')); if (!ok) process.exitCode = 1; }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const t0 = Date.now(); const lap = () => ((Date.now() - t0) / 1000).toFixed(0) + 's';

(async () => {
    // 1. 认证
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'gta6_' + Date.now() % 100000;
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', { username, password: enc, confirmPassword: enc, captcha: cap.json.captcha });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 从 GTA6 JSON 建 Track A storyboard 脚本（shotOrder→order，voice→suggestedVoice）
    const raw = JSON.parse(fs.readFileSync(SCRIPT_JSON, 'utf8'));
    const storyboard = {
        title: raw.title,
        theme: raw.theme,
        characters: (raw.characters || []).map(c => ({ name: c.name, featureTable: c.featureTable, suggestedVoice: c.voice })),
        shots: (raw.shots || []).map(s => ({
            order: s.shotOrder, sceneDescription: s.sceneDescription, cameraMovement: s.cameraMovement,
            durationSeconds: s.durationSeconds, narration: s.narration, imagePrompt: s.imagePrompt,
            characterNames: s.characterNames, shotSize: s.shotSize, dialogue: s.dialogue, dialogueCharacterName: s.dialogueCharacterName,
        })),
    };
    const sv = await req('POST', '/api/video/scripts', { idea: raw.title, storyboard });
    const script = sv.json && sv.json.data;
    check('storyboard-import', sv.status === 200 && script && script.id,
        'scriptId=' + (script && script.id) + ' shots=' + ((script && script.shots) || []).length + ' totalDur=' + ((script && script.shots) || []).reduce((a, s) => a + (s.durationSeconds || 0), 0) + 's');
    if (!script || !script.id) { console.log((sv.text || '').slice(0, 500)); return; }
    const sid = script.id;
    const shots = script.shots || [];

    // 3. 角色卡 + 画风锁 CINEMATIC_REALISM（母版 style=电影写实；非关键项，失败不早退）
    const chars = await req('GET', '/api/video/characters?scriptId=' + sid);
    const charList = (chars.json && chars.json.data) || [];
    console.log('INFO | characters=' + charList.map(c => c.name + '(feature=' + (c.featureTable ? 'Y' : 'N') + ')').join(','));
    const st = await req('PUT', '/api/video/scripts/' + sid + '/style', { style: 'CINEMATIC_REALISM' });
    console.log('INFO | style-set success=' + !!(st.json && st.json.success) + (st.json && st.json.message ? ' msg=' + st.json.message : ''));

    // 4. 切本地零云生成源（comfyui）——真正阻断项
    const ig = await req('PUT', '/api/video/images/generator?type=comfyui');
    const vg = await req('PUT', '/api/video/videos/generator?type=comfyui');
    const swOk = ig.json && ig.json.success && vg.json && vg.json.success;
    check('switch-comfyui', swOk);
    if (!swOk) { console.log('生成源切换失败，中止'); return; }

    // 5. 9 镜 SD1.5 分镜图（~8-11s/张，零云）
    let imgReady = 0;
    for (const s of shots) {
        let r = await req('POST', '/api/video/images/shot/' + sid + '/' + s.id, {});
        let d = r.json && r.json.data;
        if (!(r.status === 200 && d && d.imageStatus === 'READY')) {
            console.log('  shot ' + s.shotOrder + ' image retry @' + lap()); await sleep(2000);
            r = await req('POST', '/api/video/images/shot/' + sid + '/' + s.id, {});
            d = r.json && r.json.data;
        }
        const ok = r.status === 200 && d && d.imageStatus === 'READY';
        if (ok) imgReady++;
        check('shot ' + s.shotOrder + ' image', ok, (d ? d.imageStatus + ' ' + (d.imageUrl || '') : 'no-data') + ' @' + lap());
        if (!ok) { console.log((r.text || '').slice(0, 300)); }
    }
    check('all-storyboard-ready', imgReady === shots.length, imgReady + '/' + shots.length);
    if (process.exitCode) { console.log('分镜未全 READY，中止'); return; }

    // 6. i2v（Wan2.2 本地零云）仅 2 镜控预算：shot1(5s)→121f、shot7(6s)→145f
    let vidReady = 0;
    for (const s of shots.filter(x => I2V_ORDERS.includes(x.shotOrder))) {
        const r = await longPost('/api/video/videos/shot/' + sid + '/' + s.id, {});
        const d = r.json && r.json.data;
        const ok = r.status === 200 && r.json.success && d && d.videoStatus === 'READY';
        if (ok) vidReady++;
        check('shot ' + s.shotOrder + ' i2v(' + s.durationSeconds + 's)', ok, (d ? d.videoStatus + ' ' + (d.videoUrl || '') : (r.text || '').slice(0, 200)) + ' @' + lap());
    }
    check('i2v-targets-ready', vidReady === I2V_ORDERS.length, vidReady + '/' + I2V_ORDERS.length + '（余镜静图运镜）');

    // 7. 高清导出 16:9 720p（字幕+BGM calm+TTS+AI标识）
    const bgm = await req('GET', '/api/video/bgm');
    const calm = ((bgm.json && bgm.json.data) || []).find(b => (b.category || '').includes('calm'));
    const exp = await longPost('/api/video/export/hd', {
        scriptId: sid, resolution: '720p', aspectRatio: '16:9',
        bgmId: calm ? calm.id : null, subtitle: true, subtitleLanguage: 'zh', withLogo: true,
    });
    const video = exp.json && exp.json.data && exp.json.data.video;
    check('hd-export', exp.status === 200 && exp.json && exp.json.success && video && video.videoUrl,
        'url=' + (video && video.videoUrl) + ' duration=' + (video && video.totalDuration) + 's @' + lap());
    if (!video || !video.videoUrl) { console.log((exp.text || '').slice(0, 500)); return; }

    // 8. ffprobe 核验（1280x720，时长≈Σshot−(n−1)×0.8，含音轨）
    const local = video.videoUrl.replace(/^\/images\//, 'uploads/');
    try {
        const probe = execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_type,width,height',
            '-show_entries', 'format=duration', '-of', 'json', local], { encoding: 'utf8' });
        const pj = JSON.parse(probe);
        const v = (pj.streams || []).find(x => x.codec_type === 'video');
        const a = (pj.streams || []).find(x => x.codec_type === 'audio');
        const totalDur = shots.reduce((a2, s) => a2 + (s.durationSeconds || 0), 0);
        const expect = totalDur - (shots.length - 1) * 0.8;
        check('ffprobe-720p', !!v && v.width === 1280 && v.height === 720, v ? v.width + 'x' + v.height : 'none');
        check('ffprobe-audio', !!a, a ? a.codec_type : 'MISSING(TTS/BGM)');
        check('ffprobe-duration', Math.abs(Number(pj.format.duration) - expect) <= 2.0, pj.format.duration + 's expect≈' + expect.toFixed(1) + 's(TTS 可能微调)');
        console.log('FINAL | ' + local + ' @' + lap());
    } catch (e) { check('ffprobe', false, e.message); }
    console.log('DONE | scriptId=' + sid + ' user=' + username + '（保留供前端查看） total=' + lap());
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
