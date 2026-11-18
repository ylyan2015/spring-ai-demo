// 生成-评价-精修真实链路验证：注册登录 → 一次脚本生成（5 次 DeepSeek v4-pro 调用）
// 用法：node tools/e2e-script-refine.js [baseUrl]；前置：应用已带 DEEPSEEK_API_KEY 与
//       --logging.level.com.github.ylyan2015.springaidemo.video.VideoScriptService=DEBUG 启动
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

(async () => {
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    if (pk.status !== 200 || cap.status !== 200) { console.error('AUTH PREREQ FAIL', pk.status, cap.status); process.exit(1); }
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username: 'refine_e2e_' + Date.now() % 100000,
        password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    if (auth.status !== 200 || !auth.json || !auth.json.success) {
        console.error('REGISTER FAIL', auth.status, auth.text.slice(0, 300)); process.exit(1);
    }
    console.log('PASS | register, cookie acquired');

    console.log('INFO | generate start (5 次 v4-pro 思考调用，预计 5-15 分钟)…');
    const t0 = Date.now();
    const gen = await req('POST', '/api/video/scripts/generate', {
        idea: '外卖骑手在暴雨夜送到一位独居老人的最后一单，发现老人是他多年前走失的祖母'
    });
    const ms = Date.now() - t0;
    const script = gen.json && gen.json.data;
    if (!script || !script.id) {
        console.error('GENERATE FAIL', gen.status, gen.text.slice(0, 500)); process.exit(1);
    }
    console.log('PASS | generate ' + (ms / 1000).toFixed(0) + 's scriptId=' + script.id);
    console.log(JSON.stringify({
        title: script.title, theme: script.theme, aspectRatio: script.aspectRatio,
        shots: (script.shots || []).map(s => ({
            order: s.shotOrder, dur: s.durationSeconds, size: s.shotSize,
            narration: s.narration, dialogue: s.dialogue, imagePrompt: s.imagePrompt
        }))
    }, null, 1));
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
