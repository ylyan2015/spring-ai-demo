// 评论工作台（Phase 5 Track B）全链路验证脚本
// 前置：应用已启动，配置 video.ffmpeg-path/ffprobe-path/python-command（edge-tts）、
//       video.commentary.import-dir 指向含约定命名素材的目录（epNN_标签_名称.mp4/png）、
//       video.commentary.attribution-font（中文字体）、--auth.captcha.debug-text=true
// 用法：node tools/e2e-commentary.js [baseUrl]
// 流程：认证 → 页面渲染 → 导入评论脚本 → 扫描入库 → 镜头绑定 → 同步导出 → 异步导出轮询 → 引用占比护栏拒绝
const crypto = require('crypto');
const BASE = process.argv[2] || 'http://localhost:8080';
let cookie = '';

async function req(method, path, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + path, {
        method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual'
    });
    const setCookie = resp.headers.get('set-cookie');
    if (setCookie) cookie = setCookie.split(';')[0];
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* non-JSON */ }
    return { status: resp.status, json, text };
}

function rsaEncrypt(publicKeyBase64, plain) {
    const der = Buffer.from(publicKeyBase64, 'base64');
    const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    return crypto.publicEncrypt({
        key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256'
    }, Buffer.from(plain, 'utf8')).toString('base64');
}

function check(name, ok, detail) {
    console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''));
    if (!ok) process.exitCode = 1;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
    // 1. 认证
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'e2e_comm_' + Date.now() % 100000;
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 评论工作台页面渲染（登录态）
    const page = await req('GET', '/commentary-studio');
    const markers = ['quoteRatioBadge', 'scanAssets', 'exportCommentaryAsync', 'pollJob', 'updateQuoteRatio'];
    const missing = markers.filter(m => !page.text.includes(m));
    check('commentary page render', page.status === 200 && missing.length === 0,
        missing.length ? 'missing=' + missing.join(',') : 'all markers present');

    // 3. 导入评论脚本（3 镜：图旁白 / 视频引用 / 图旁白）
    const imp = await req('POST', '/api/video/scripts/commentary/import', {
        title: 'E2E 漫评', theme: '测试', shots: [
            { durationSeconds: 10, narration: '这是第一个镜头的旁白解说，城市街景。', imagePrompt: '城市街景' },
            { durationSeconds: 5, narration: '插入引用片段。', insert: { attribution: '来源：第1话' } },
            { durationSeconds: 10, narration: '第三个镜头总结，夜景。', imagePrompt: '夜景' }
        ]
    });
    const script = imp.json && imp.json.data;
    check('import commentary', imp.status === 200 && script && script.id && script.format === 'commentary',
        'scriptId=' + (script && script.id) + ' shots=' + (script && script.shots && script.shots.length));
    if (!script || !script.id) { console.log(imp.text.slice(0, 500)); return; }
    const shots = script.shots;

    // 4. 扫描入库（配置目录约定命名素材）
    const scan = await req('POST', '/api/video/assets/scan', {});
    const assets = (scan.json && scan.json.data) || [];
    const videoAsset = assets.find(a => a.kind === 'VIDEO');
    const imageAsset = assets.find(a => a.kind === 'IMAGE');
    check('scan ingest', scan.status === 200 && videoAsset && imageAsset,
        'assets=' + assets.length + ' video=' + (videoAsset && videoAsset.tags) + ' image=' + (imageAsset && imageAsset.tags));
    if (!videoAsset || !imageAsset) { console.log(scan.text.slice(0, 500)); return; }

    // 5. 镜头绑定（视频→片段；图片→主视觉）
    const bindV = await req('POST', `/api/video/scripts/${script.id}/shots/${shots[1].id}/asset`,
        { assetId: videoAsset.id, asVideo: true });
    check('bind video clip', bindV.status === 200 && bindV.json.data.videoStatus === 'READY',
        'videoUrl=' + (bindV.json.data && bindV.json.data.videoUrl) + ' dur=' + (bindV.json.data && bindV.json.data.durationSeconds));
    for (const idx of [0, 2]) {
        const b = await req('POST', `/api/video/scripts/${script.id}/shots/${shots[idx].id}/asset`,
            { assetId: imageAsset.id, asVideo: false });
        check('bind image shot ' + (idx + 1), b.status === 200 && b.json.data.imageStatus === 'READY', '');
    }

    // 6. 同步高清导出（验证 t7 媒体就绪修正：视频绑定镜无 imageUrl 仍可导出 + 自动对齐 + 出处水印 + 护栏放行）
    const exp = await req('POST', '/api/video/export/hd', {
        scriptId: script.id, aspectRatio: '9:16', subtitle: true, subtitleLanguage: 'zh', withLogo: false
    });
    const video = exp.json && exp.json.data && exp.json.data.video;
    check('sync HD export', exp.status === 200 && exp.json.success && video && video.videoUrl,
        'url=' + (video && video.videoUrl) + ' dur=' + (video && video.totalDuration) + 's narration=' + (video && video.hasNarration) +
        (exp.json && !exp.json.success ? ' msg=' + exp.json.message : ''));

    // 7. 异步导出 + 轮询终态
    const asyncJob = await req('POST', '/api/video/export/hd/async', {
        scriptId: script.id, aspectRatio: '9:16', subtitle: false, withLogo: false
    });
    const jobId = asyncJob.json && asyncJob.json.data && asyncJob.json.data.jobId;
    check('async submit', asyncJob.status === 200 && jobId && asyncJob.json.data.status === 'RUNNING', 'jobId=' + jobId);
    if (jobId) {
        let final = null;
        for (let i = 0; i < 60; i++) {
            await sleep(3000);
            const st = await req('GET', '/api/video/export/jobs/' + jobId);
            const s = st.json && st.json.data && st.json.data.status;
            if (s === 'SUCCESS' || s === 'FAILED') { final = st.json.data; break; }
        }
        check('async job terminal', final && final.status === 'SUCCESS',
            final ? 'status=' + final.status + (final.message ? ' msg=' + final.message : '') : 'timeout');
    }

    // 8. 引用占比护栏拒绝（高占比脚本：引用镜 10s / 总 15s = 0.67 > 0.2）
    const bad = await req('POST', '/api/video/scripts/commentary/import', {
        title: 'E2E 超限', shots: [
            { durationSeconds: 10, narration: '引用', insert: { attribution: '来源：第2话' } },
            { durationSeconds: 5, narration: '原创' }
        ]
    });
    const badScript = bad.json && bad.json.data;
    const guard = await req('POST', '/api/video/export/hd', { scriptId: badScript.id, aspectRatio: '9:16' });
    check('insert-ratio guard rejects', guard.json && guard.json.success === false &&
        /引用占比超限/.test(guard.json.message || ''), 'msg=' + (guard.json && guard.json.message));

    // 9. 导出历史
    const hist = await req('GET', '/api/video/exports/' + script.id);
    const records = (hist.json && hist.json.data) || [];
    check('export history', hist.status === 200 && records.length > 0, records.length + ' record(s)');

    console.log('DONE | scriptId=' + script.id);
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });