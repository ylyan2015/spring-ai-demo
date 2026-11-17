// Phase 5 Track B 真实脚本端到端实测：FX《战士久留美》评论类脚本 → B站成片
// 前置：应用已启动，配置 video.ffmpeg-path/ffprobe-path/python-command(edge-tts)、
//       video.commentary.attribution-font(msyh.ttc)、image.generator=dashscope(或 zhipu，Key 已注入)
// 用法：
//   node tools/e2e-fx-kurumi.js probe   # 导入→直接导出，实测引用占比护栏（预期拒绝，拿证据）
//   node tools/e2e-fx-kurumi.js full    # 导入→fallback 清水印→真实生图→导出 B站 16:9 成片
//   node tools/e2e-fx-kurumi.js full [baseUrl]
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const MODE = process.argv[2] || 'probe';
const BASE = process.argv[3] || 'http://localhost:8080';
const SCRIPT_JSON = path.join(__dirname, '..', 'docs', 'story', 'FX', 'kurumi-commentary-domestic.json');
let cookie = '';

async function req(method, p, body, isForm) {
    const headers = {};
    if (!isForm) headers['Content-Type'] = 'application/json';
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + p, {
        method, headers, body: body ? (isForm ? body : JSON.stringify(body)) : undefined, redirect: 'manual'
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
const log = (...a) => console.log('...', ...a);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function auth() {
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'fx_kurumi_' + (Date.now() % 100000);
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const r = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register/login', r.status === 200 && r.json && r.json.success, 'user=' + username);
    return username;
}

async function importFx() {
    const json = JSON.parse(fs.readFileSync(SCRIPT_JSON, 'utf8'));
    const imp = await req('POST', '/api/video/scripts/commentary/import', json);
    const script = imp.json && imp.json.data;
    check('import FX commentary JSON', imp.status === 200 && script && script.format === 'commentary',
        'scriptId=' + (script && script.id) + ' shots=' + (script && script.shots && script.shots.length));
    if (!script || !script.id) { log(imp.text.slice(0, 800)); return null; }
    return script;
}

function ratioOf(shots) {
    let total = 0, insert = 0;
    for (const s of shots) {
        const d = s.durationSeconds || 5; total += d;
        if (s.insertAttribution && s.insertAttribution.trim()) insert += d;
    }
    return { total, insert, pct: total ? (insert * 100 / total) : 0 };
}

(async () => {
    await auth();
    const script = await importFx();
    if (!script) return;
    const shots = script.shots;

    const att = shots.filter(s => s.insertAttribution && s.insertAttribution.trim());
    const r = ratioOf(shots);
    log('带出处水印(引用)镜数:', att.length, '| 各镜出处:', att.map(s => s.shotOrder + '#' + s.insertAttribution).join(', '));
    log('服务端护栏口径引用占比:', r.insert + 's / ' + r.total + 's = ' + r.pct.toFixed(1) + '%',
        '| 脚本设计 insertPolicy.ratio:', JSON.parse(fs.readFileSync(SCRIPT_JSON, 'utf8')).insertPolicy.ratio * 100 + '%');

    // ===== 引用占比护栏实测：直接导出应被拒（整镜计引用 48.9% > 20%）=====
    const exp1 = await req('POST', '/api/video/export/hd', {
        scriptId: script.id, aspectRatio: '16:9', subtitle: true, subtitleLanguage: 'zh', withLogo: false
    });
    const guardMsg = exp1.json && exp1.json.message || '';
    check('护栏实测：导入即导出被引用占比拒绝', exp1.json && exp1.json.success === false && /引用占比超限/.test(guardMsg),
        'status=' + exp1.status + ' msg=' + guardMsg);

    if (MODE !== 'full') {
        console.log('PROBE DONE | scriptId=' + script.id + ' （完整出片请用 full 模式）');
        return;
    }

    // ===== fallback：番剧截图素材缺失 → 按脚本 §1.4「用该镜 imagePrompt 原创画面顶替」清水印 =====
    log('fallback：清除 5 处引用镜的 insertAttribution（无真实引用画面，不挂出处水印）');
    for (const s of att) {
        const u = await req('PUT', `/api/video/scripts/${script.id}/shots/${s.id}`, { insertAttribution: '' });
        if (!(u.json && u.json.success !== false)) log('  镜', s.shotOrder, '水印已清');
    }
    const after = ratioOf((await req('GET', '/api/video/scripts/' + script.id)).json.data.shots);
    check('fallback 后引用占比归零（可过护栏）', after.insert === 0, 'insert=' + after.insert + '/' + after.total);

    // ===== 真实生成 10 镜原创画面（同步端点，逐镜；失败重试一次）=====
    log('开始真实生成 10 镜原创画面（image.generator=dashscope/zhipu）…');
    let genOk = 0;
    for (const s of shots) {
        let done = false;
        for (let attempt = 1; attempt <= 2 && !done; attempt++) {
            const g = await req('POST', `/api/video/images/shot/${script.id}/${s.id}`, {});
            const st = g.json && g.json.data && g.json.data.imageStatus;
            if (st === 'READY') { done = true; genOk++; log('  镜', s.shotOrder, '图 READY'); }
            else { log('  镜', s.shotOrder, '生图 attempt', attempt, 'status=' + st, 'msg=' + (g.json && g.json.message || '')); await sleep(2000); }
        }
    }
    check('10 镜原创画面生成就绪', genOk === shots.length, 'ready=' + genOk + '/' + shots.length);
    if (genOk !== shots.length) {
        log('部分镜生图未就绪，仍尝试导出以观察 isShotMediaReady 行为');
    }

    // ===== 导出 B站 16:9 成片（异步 + jobs 轮询）=====
    log('提交异步导出 16:9 720p（B站）…');
    const job = await req('POST', '/api/video/export/hd/async', {
        scriptId: script.id, aspectRatio: '16:9', resolution: '720p',
        subtitle: true, subtitleLanguage: 'zh', withLogo: true
    });
    const jobId = job.json && job.json.data && job.json.data.jobId;
    check('异步导出提交', job.status === 200 && jobId, 'jobId=' + jobId);
    let final = null;
    if (jobId) {
        for (let i = 0; i < 120; i++) {
            await sleep(5000);
            const st = await req('GET', '/api/video/export/jobs/' + jobId);
            const s = st.json && st.json.data && st.json.data.status;
            process.stdout.write('  poll#' + (i + 1) + ' ' + s + '\r');
            if (s === 'SUCCESS' || s === 'FAILED') { final = st.json.data; break; }
        }
    }
    const video = final && final.data && final.data.video || final;
    check('B站 16:9 成片导出成功', final && final.status === 'SUCCESS',
        final ? 'status=' + final.status + (final.message ? ' msg=' + final.message : '') : 'timeout');
    if (final && final.status === 'SUCCESS') {
        log('成片 url=', (video && (video.videoUrl || (video.video && video.video.videoUrl))) || JSON.stringify(final).slice(0, 300));
        log('时长=', (video && video.totalDuration) || (final.data && final.data.durationSeconds));
    }

    const hist = await req('GET', '/api/video/exports/' + script.id);
    const records = (hist.json && hist.json.data) || [];
    log('导出历史记录:', records.length, '条 |', records.map(x => x.status + (x.videoUrl ? '(有片)' : '')).join(','));
    console.log('FULL DONE | scriptId=' + script.id);
})().catch(e => { console.error('SCRIPT ERROR:', e.stack || e.message); process.exitCode = 1; });
