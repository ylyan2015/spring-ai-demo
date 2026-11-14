// 短视频工作室全链路验证脚本（S5-2）
// 前置：应用已启动且配置 DEEPSEEK_API_KEY + DASHSCOPE_API_KEY；
//       验证质量评估时以 IMAGE_QUALITY_ENABLED=true 启动应用（或 image.quality.enabled: true）
// 用法：node tools/e2e-video-full.js [baseUrl]
// 流程：认证 → 脚本生成 → 逐镜头图片生成（含质量评分）→ 480p 预览导出 → 导出历史
// 注意：会消耗少量 DashScope/DeepSeek API 额度；测试脚本保留在账号下供前端查看，不自动删除
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

(async () => {
    // 1. 认证
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    check('auth prerequisites', pk.status === 200 && cap.status === 200, '');
    const username = 'e2e_full_' + Date.now() % 100000;
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 脚本生成（DeepSeek）
    const gen = await req('POST', '/api/video/scripts/generate', {
        idea: '清晨的城市咖啡师：三分钟治愈系短片，3个镜头'
    });
    const script = gen.json && gen.json.data;
    check('script-generate', gen.status === 200 && script && script.id,
        'scriptId=' + (script && script.id));
    if (!script || !script.id) { console.log(gen.text.slice(0, 500)); return; }

    // 3. 逐镜头图片生成（DashScope；启用质量评估时含评分/低分重试）
    let scored = 0;
    for (const shot of script.shots || []) {
        const r = await req('POST', '/api/video/images/shot/' + script.id + '/' + shot.id, {});
        const d = r.json && r.json.data;
        const ok = r.status === 200 && r.json.success && d && d.imageStatus === 'READY';
        check('shot ' + shot.shotOrder + ' image', ok,
            'qualityScore=' + (d ? d.qualityScore : 'n/a') + (ok ? '' : ' msg=' + (r.json && r.json.message)));
        if (d && d.qualityScore != null) scored++;
        if (!ok) return;
    }
    console.log('INFO | quality scores present on ' + scored + '/' + (script.shots || []).length +
        ' shots (0 表示未启用 image.quality.enabled)');

    // 4. 480p 预览导出（FFmpeg 合成 + TTS + 存储）
    const exp = await req('POST', '/api/video/export/preview', {
        scriptId: script.id, aspectRatio: '16:9', bgmId: null, subtitle: true, subtitleLanguage: 'zh'
    });
    const video = exp.json && exp.json.data && exp.json.data.video;
    check('preview export', exp.status === 200 && exp.json.success && video && video.videoUrl,
        'url=' + (video && video.videoUrl) + ' duration=' + (video && video.totalDuration) + 's' +
        (exp.json && !exp.json.success ? ' msg=' + exp.json.message : ''));

    // 5. 导出历史
    const hist = await req('GET', '/api/video/exports/' + script.id);
    const records = (hist.json && hist.json.data) || [];
    check('export history', hist.status === 200 && records.length > 0, records.length + ' record(s)');

    console.log('DONE | scriptId=' + script.id + '（保留在前端脚本列表，可登录后继续操作/删除）');
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });