// 发布元数据链路验证（计划 docs/superpowers/plans/2026-11-19-publish-metadata-bilibili.md · Task 7/9 配套）
// 前置：应用已启动（默认 http://localhost:8080，profile deepseek,h2）
// 用法：node tools/e2e-publish-meta.js [baseUrl]
//       LLM 标题候选默认不跑（省额度）：PUBLISH_E2E_LLM=1 node tools/e2e-publish-meta.js
// 检查项：规格下发 → 确定性派生（五节顺序/事实原文照抄/出处去重/近似轴标记）
//        → 400 违规码 → 人工值保存与部分更新不刷人工值 → 面板 DOM 结构
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
    try { json = JSON.parse(text); } catch (e) { /* non-JSON（页面断言走 text） */ }
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

function occurrences(text, needle) {
    let n = 0, i = 0;
    while ((i = String(text).indexOf(needle, i)) >= 0) { n++; i += needle.length; }
    return n;
}

function inOrder(text, list) {
    let prev = -1;
    for (const s of list) {
        const at = String(text).indexOf(s);
        if (at < 0 || at <= prev) return false;
        prev = at;
    }
    return true;
}

(async () => {
    // ---------- 0. 认证 ----------
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    check('auth prerequisites', pk.status === 200 && cap.status === 200, '');
    const username = 'e2e_pub_' + (Date.now() % 100000);
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // ---------- 1. 评论脚本（含事实台账与两处相同出处） ----------
    const idea = '作者 18 年外汇，峰值亏 1400 万日元；共 17 个镜头。纯属娱乐。';
    const sc = await req('POST', '/api/video/scripts/commentary/import', {
        title: 'e2e 发布元数据 ' + (Date.now() % 100000),
        theme: '用一部番讲一个账户',
        idea: idea,
        shots: [
            { durationSeconds: 5, narration: '画久留美的人，爆仓了。' },
            { durationSeconds: 6, narration: '番里她只负责可爱和赚钱。',
              insert: { attribution: '图取自作品官网 fxkurumi-info.com' } },
            { durationSeconds: 4, narration: '第三镜还是那张图。',
              insert: { attribution: '图取自作品官网 fxkurumi-info.com' } }
        ]
    });
    const scriptId = sc.json && sc.json.data && sc.json.data.id;
    check('script imported', sc.status === 200 && !!scriptId, 'scriptId=' + scriptId);
    if (!scriptId) { console.log(sc.text.slice(0, 400)); return; }

    // ---------- 2. 规格下发（前端唯一的规则源） ----------
    const spec = await req('GET', '/api/video/publish/spec?platform=BILIBILI');
    const s = (spec.json && spec.json.data) || {};
    check('spec hard limits', s.titleMax === 80 && s.tagMax === 10 && s.descMax === 2000,
        JSON.stringify({ t: s.titleMax, g: s.tagMax, d: s.descMax }));
    check('spec categories carry tid', (s.categories || []).some(c => c.tid === 253)
        && (s.categories || []).some(c => c.tid === 182), JSON.stringify(s.categories));
    check('spec ships tid hint + banned words', String(s.tidHint || '').includes('?tid=')
        && (s.bannedWords || []).includes('必看'), 'banned=' + JSON.stringify(s.bannedWords));
    const badPlatform = await req('GET', '/api/video/publish/spec?platform=DOUYIN');
    check('unsupported platform is 400', badPlatform.status === 400, 'status=' + badPlatform.status);

    // ---------- 3. 确定性派生（llm=false，零模型调用） ----------
    const META = '/api/video/publish/' + scriptId + '/meta?platform=BILIBILI';
    const DRAFT = '/api/video/publish/' + scriptId + '/draft?platform=BILIBILI';
    const dr = await req('GET', DRAFT + '&llm=false');
    const d = (dr.json && dr.json.data) || {};
    check('draft ok', dr.status === 200 && !!d.description, 'status=' + dr.status);
    check('draft has five sections in order', inOrder(d.description, [
        '【本片事实】', '【素材出处】', '【AI 使用说明】', '【不构成投资建议】', '【时间轴】']), '');
    check('draft copies fact verbatim', String(d.description).includes('1400 万日元'), '');
    check('draft refuses unsourced opinion', !String(d.description).includes('纯属娱乐'),
        '无数字的主观句不得混进【本片事实】');
    check('attribution deduplicated', occurrences(d.description, 'fxkurumi-info.com') === 1,
        '三镜两处相同出处只写一次');
    check('approx axis flagged when no export yet', d.approxTimestamps === true, '');
    check('llm=false ships no candidates', Array.isArray(d.titleCandidates) && d.titleCandidates.length === 0, '');
    check('tags keep one slot for viewers', Array.isArray(d.tags) && d.tags.length <= 9,
        'tags=' + JSON.stringify(d.tags));

    // ---------- 4. 400 与违规码（前端靠 code 定位输入框） ----------
    const tooLong = await req('PUT', META, { title: '久'.repeat(81) });
    check('overlong title is 400 + TITLE_TOO_LONG', tooLong.status === 400
        && (tooLong.json.violations || []).some(v => v.code === 'TITLE_TOO_LONG' && v.field === 'title'),
        'msg=' + (tooLong.json && tooLong.json.message));
    const dupTag = await req('PUT', META, { tags: ['解说', ' 解说 ', ''] });
    // 空标签与重复标签是提示级（面板把 chip 标灰），落库前由服务层归一，不因此拒收；
    // 真正拒收的是硬上限（上面那条 TITLE_TOO_LONG）
    check('blank/duplicate tags are normalised on save, not rejected', dupTag.status === 200
        && JSON.stringify(dupTag.json.data.tags) === '["解说"]',
        'tags=' + JSON.stringify(dupTag.json && dupTag.json.data && dupTag.json.data.tags));
    const badTid = await req('PUT', META, { categoryTid: 999999 });
    check('unknown tid is 400', badTid.status === 400, 'msg=' + (badTid.json && badTid.json.message));

    // ---------- 5. 人工值保存 → 回读 → 部分更新不刷人工值 ----------
    const saved = await req('PUT', META, {
        title: 'e2e 人工标题', description: '人工简介', tags: ['福贺久留美', '动漫杂谈'],
        categoryTid: 253, declaration: '含AI生成内容', collection: '钱坑漫评', pinnedComment: '原置顶'
    });
    check('manual save ok + source=MANUAL', saved.status === 200
        && saved.json.data.source === 'MANUAL' && saved.json.data.title === 'e2e 人工标题',
        'msg=' + (saved.json && saved.json.message));
    const back = await req('GET', META);
    check('read back stored row', back.json.data.fallbackToDraft === false
        && back.json.data.title === 'e2e 人工标题' && (back.json.data.tags || []).length === 2, '');
    const partial = await req('PUT', META, { pinnedComment: '改过的置顶' });
    check('partial update keeps manual title', partial.status === 200
        && partial.json.data.title === 'e2e 人工标题'
        && partial.json.data.pinnedComment === '改过的置顶', '');
    const mixedBad = await req('PUT', META, { title: '久'.repeat(81), pinnedComment: 'x' });
    check('overlong title is rejected even alongside valid keys', mixedBad.status === 400, '');

    // ---------- 6. 面板结构（服务端真实渲染的页面，不只是 classpath 副本） ----------
    const page = await req('GET', '/commentary-studio');
    check('publish panel markup served', page.status === 200
        && page.text.includes('id="publishPanel"')
        && page.text.includes('function openPublishPanel')
        && page.text.includes('function copyPublishPack'), 'status=' + page.status);
    check('panel takes limits from spec endpoint', page.text.includes('publishSpec.titleMax')
        && page.text.includes('/api/video/publish/spec'), '');

    // ---------- 7. 可选：LLM 标题候选（要额度） ----------
    if (process.env.PUBLISH_E2E_LLM === '1') {
        const full = await req('GET', DRAFT + '&llm=true');
        const cands = (full.json.data || {}).titleCandidates || [];
        check('llm candidates present and bounded', cands.length > 0 && cands.length <= 3,
            'n=' + cands.length);
        check('llm candidates within title limit', cands.every(t => [...t].length <= 80), '');
        check('llm candidates introduce no new numbers', cands.every(t =>
            !/1\.4|亿/.test(t)), 'candidates=' + JSON.stringify(cands));
        console.log('INFO | candidates: ' + JSON.stringify(cands));
    } else {
        console.log('INFO | 跳过 LLM 候选断言（PUBLISH_E2E_LLM=1 打开）');
    }

    console.log('DONE | scriptId=' + scriptId + '（保留在账号 ' + username + ' 下，可在评论工作台复看面板）');
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
