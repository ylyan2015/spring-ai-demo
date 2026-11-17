// g5b：真实 Spring 链路验证成片归档到 uploads/final/（无 GPU，绑既有 PNG 直接导出）
// 前置：应用以 --auth.captcha.debug-text=true 启动于 8080
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8080';
const TITLE = '验收-成片归档';
// 两张既有分镜 PNG（相对 uploads，access 前缀 /images/）
const IMAGES = [
    '/images/2026/11/15/3d707a6e-f03f-4f25-8437-ecd4a073565d_comfyui_img_1794755468837_a7ca018e-79e7-44d8-b257-b53d5ee59c85.png',
    '/images/2026/11/15/463609b9-b0fb-4482-96e0-865e484a5426_comfyui_img_1794755395863_e5962f62-c31a-480a-a176-f72288b9c546.png',
];
let cookie = '';
async function req(method, p, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual' });
    const sc = resp.headers.get('set-cookie'); if (sc) cookie = sc.split(';')[0];
    const text = await resp.text(); let json = null; try { json = JSON.parse(text); } catch (e) {}
    return { status: resp.status, json, text };
}
function rsaEncrypt(pub, plain) {
    const key = crypto.createPublicKey({ key: Buffer.from(pub, 'base64'), format: 'der', type: 'spki' });
    return crypto.publicEncrypt({ key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(plain)).toString('base64');
}
function check(name, ok, detail) { console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : '')); if (!ok) process.exitCode = 1; }

(async () => {
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'finalchk_' + (Date.now() % 100000);
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', { username, password: enc, confirmPassword: enc, captcha: cap.json.captcha });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 建脚本（2 静图镜，无旁白避免 edge-tts 依赖）
    const sv = await req('POST', '/api/video/scripts', {
        idea: TITLE,
        storyboard: {
            title: TITLE, theme: '归档验收',
            shots: [
                { order: 1, sceneDescription: '镜一', cameraMovement: '固定', durationSeconds: 3, imagePrompt: 'p1' },
                { order: 2, sceneDescription: '镜二', cameraMovement: '固定', durationSeconds: 3, imagePrompt: 'p2' },
            ],
        },
    });
    const script = sv.json && sv.json.data;
    check('storyboard-create', sv.status === 200 && script && script.id, 'scriptId=' + (script && script.id));
    if (!script || !script.id) { console.log((sv.text || '').slice(0, 400)); return; }
    const sid = script.id;

    // 绑既有 PNG + 标 READY（绕开 ComfyUI 生图）
    for (let i = 0; i < script.shots.length; i++) {
        const s = script.shots[i];
        const up = await req('PUT', '/api/video/scripts/' + sid + '/shots/' + s.id,
            { imageUrl: IMAGES[i], imageStatus: 'READY' });
        const d = up.json && up.json.data;
        check('bind-shot-' + s.shotOrder, up.status === 200 && d && d.imageStatus === 'READY', d && d.imageStatus);
    }

    // HD 导出（触发 storeVideo→uploadFinal）
    const exp = await req('POST', '/api/video/export/hd', {
        scriptId: sid, resolution: '720p', aspectRatio: '16:9', subtitle: false, withLogo: false,
    });
    const video = exp.json && exp.json.data && exp.json.data.video;
    const url = video && video.videoUrl;
    check('hd-export', exp.status === 200 && exp.json && exp.json.success && !!url, 'url=' + url);
    if (!url) { console.log((exp.text || '').slice(0, 500)); return; }
    check('video-in-final-dir', url.includes('/images/final/'), url);
    const diskVideo = path.join(__dirname, '..', 'uploads', url.replace(/^\/images\//, ''));
    check('video-file-on-disk', fs.existsSync(diskVideo), diskVideo);
    check('video-friendly-name', /验收-成片归档-视频.*\.mp4$/.test(url), path.basename(url));

    // 封面生成（触发 renderVideoCover→uploadFinal）
    const cov = await req('POST', '/api/video/cover/generate', { scriptId: sid });
    const coverUrl = cov.json && cov.json.data && cov.json.data.coverUrl;
    check('cover-in-final-dir', cov.status === 200 && !!coverUrl && coverUrl.includes('/images/final/'), coverUrl || (cov.text || '').slice(0, 300));
    if (coverUrl) {
        const diskCover = path.join(__dirname, '..', 'uploads', coverUrl.replace(/^\/images\//, ''));
        check('cover-file-on-disk', fs.existsSync(diskCover), diskCover);
    }
    console.log('DONE | final/ 落地验证结束，请停应用并检查 uploads/final/');
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
