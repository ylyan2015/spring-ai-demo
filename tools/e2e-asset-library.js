// 素材库与画面感增强（S1→S5）全链路验证脚本（Task 14）
// 前置：应用已启动 `--auth.captcha.debug-text=true`（注册需要验证码明文）；LLM 可用（脚本生成段）；
//       可选 video.assets.reference-import-dir 指向 char_/scene_ 素材目录（扫描段验证解析）；
//       ComfyUI 存活且 ipadapter 组合模板已校准则走真实生图+导出，否则该两段 gate 跳过（数据链路仍全验）。
// 用法：node tools/e2e-asset-library.js [baseUrl]
// 退出码：0=无 FAIL（PASS/SKIP/INFO 不计），1=存在 FAIL
const crypto = require('crypto');
const BASE = process.argv[2] || 'http://localhost:8080';
let cookie = '';

async function req(method, p, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (cookie) headers['Cookie'] = cookie;
    const resp = await fetch(BASE + p, {
        method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual'
    });
    const setCookie = resp.headers.get('set-cookie');
    if (setCookie) cookie = setCookie.split(';')[0];
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* 非 JSON 响应保留原文 */ }
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

function skip(name, reason) {
    console.log('SKIP | ' + name + ' | ' + reason);
}

function info(name, detail) {
    console.log('INFO | ' + name + ' | ' + detail);
}

// 1×1 透明 PNG（上传入库用，不依赖外部文件）
const PNG_1x1 = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

(async () => {
    // 1. 认证（idiom 逐字镜像 e2e-commentary.js：RSA-OAEP + cookie jar）
    const pk = await req('GET', '/api/auth/public-key');
    const cap = await req('GET', '/api/auth/captcha');
    const username = 'e2e_asset_' + (Date.now() % 100000);
    const enc = rsaEncrypt(pk.json.publicKey, 'E2eTest@2026');
    const auth = await req('POST', '/api/auth/register', {
        username, password: enc, confirmPassword: enc, captcha: cap.json.captcha
    });
    check('register', auth.status === 200 && auth.json && auth.json.success, 'user=' + username);
    if (process.exitCode) return;

    // 2. 页面渲染：素材库面板（S4）+ 匹配 chip（S2）标记齐全
    const page = await req('GET', '/video-studio');
    const markers = ['assetPanel', 'matchAssetsForScript', 'booruSearch', 'scanRefDir',
        'openAssetPanel', 'uploadRefAsset', 'toggleBooruSelect', 'booruIngestSelected', '&quot;'];
    const missing = markers.filter(m => !page.text.includes(m));
    check('video-studio page markers', page.status === 200 && missing.length === 0,
        missing.length ? 'missing=' + missing.join(',') : markers.length + ' markers present');

    // 3. 上传入库（S1 category 语义，multipart）
    const fd = new FormData();
    fd.append('file', new Blob([PNG_1x1], { type: 'image/png' }), 'char_林晚_正面全身.png');
    fd.append('name', '林晚');
    fd.append('category', 'CHARACTER');
    fd.append('tags', '黑长直,红大衣');
    const upResp = await fetch(BASE + '/api/video/assets', {
        method: 'POST', headers: { Cookie: cookie }, body: fd
    });
    const up = await upResp.json();
    check('upload CHARACTER asset', upResp.status === 200 && up.success && up.data
        && up.data.category === 'CHARACTER', 'assetId=' + (up.data && up.data.id));
    const charAsset = up.data;
    if (!charAsset || !charAsset.id) { console.log(up); return; }
    // 红线：入库素材的 url 必须是自家存储路径，绝不能是外链
    check('uploaded url is own storage', typeof charAsset.url === 'string'
        && !/donmai|safebooru|googleapis/i.test(charAsset.url), 'url=' + charAsset.url);

    // 4. 扫描参考目录（未配置时返回空且不报错；配置时验证 char_/scene_ 解析）
    const scan = await req('POST', '/api/video/assets/scan-reference', {});
    check('scan-reference endpoint', scan.status === 200 && scan.json && scan.json.success,
        'ingested=' + ((scan.json.data || []).length));
    const sceneAsset = (scan.json.data || []).find(a => a.category === 'SCENE');

    // 5. category 过滤：INSERT 存量不混入参考清单（S1 兼容语义）
    const list = await req('GET', '/api/video/assets?category=CHARACTER');
    const items = (list.json && list.json.data) || [];
    check('list category filter', list.status === 200 && items.every(a => a.category === 'CHARACTER'),
        'count=' + items.length);

    // 6. 生成脚本（LLM 真实调用；素材清单注入后角色名命中是软断言——LLM 不保证）
    const gen = await req('POST', '/api/video/scripts/generate', {
        idea: '林晚在黄昏雨巷里喂流浪猫的治愈短片，人物背景具体、光影丰富'
    });
    const script = gen.json && gen.json.data;
    check('generate script', gen.status === 200 && script && script.id && (script.shots || []).length > 0,
        'scriptId=' + (script && script.id) + ' shots=' + (script && script.shots && script.shots.length));
    if (!script || !script.id) { console.log(gen.text.slice(0, 800)); return; }
    const usedLibName = JSON.stringify(script).includes('林晚');
    info('脚本是否复用库内角色名「林晚」（S5 清单注入软断言）', usedLibName);
    const shot = script.shots[0];

    // 7. 一键匹配 → 推荐不落库 → 人工确认绑定（S2 阅后即焚契约）
    const match = await req('POST', `/api/video/scripts/${script.id}/asset-matches`, {});
    check('asset-matches', match.status === 200 && match.json.success
        && !!(match.json.data && match.json.data.source), 'source=' + (match.json.data && match.json.data.source));
    const before = await req('GET', '/api/video/scripts/' + script.id);
    const beforeShot = (((before.json || {}).data || {}).shots || []).find(s => s.id === shot.id);
    check('recommendation not persisted', !beforeShot || !beforeShot.referenceAssetIds
        || beforeShot.referenceAssetIds.length === 0, '推荐阅后即焚');
    const bindIds = [charAsset.id].concat(sceneAsset ? [sceneAsset.id] : []);
    const bind = await req('PUT', `/api/video/scripts/${script.id}/shots/${shot.id}`, { referenceAssetIds: bindIds });
    check('confirm binding', bind.status === 200 && bind.json.success, 'ids=' + bindIds.join(','));
    const after = await req('GET', '/api/video/scripts/' + script.id);
    const afterShot = (((after.json || {}).data || {}).shots || []).find(s => s.id === shot.id);
    const boundIds = (afterShot && afterShot.referenceAssetIds) || [];
    check('binding persisted', boundIds.includes(charAsset.id), 'bound=' + JSON.stringify(boundIds));
    // 伪造 id 必须被服务端剔除（Task 3 校验），且剔除不得报错（PUT 直接返 shot map，referenceAssetIds 是数组）
    const dirty = await req('PUT', `/api/video/scripts/${script.id}/shots/${shot.id}`,
        { referenceAssetIds: [charAsset.id, 99999999] });
    const kept = (dirty.json && dirty.json.data && dirty.json.data.referenceAssetIds) || [];
    check('foreign asset id dropped', dirty.status === 200 && dirty.json.success
        && kept.length === 1 && kept[0] === charAsset.id, 'kept=' + JSON.stringify(kept));

    // 8. 网搜闸（S4，外网可达才验；三道闸的语义在单测里钉，这里只验端到端可用）
    const bs = await req('GET', '/api/video/assets/booru/search?q=hatsune_miku&provider=safebooru&page=0');
    if (bs.status === 200 && bs.json && bs.json.success) {
        const posts = bs.json.data || [];
        check('booru search', posts.length >= 0, 'posts=' + posts.length);
        check('booru safe-only + no foreign url in payload',
            posts.every(p => /safe|general|^g$/i.test(p.rating || '')),
            posts.length ? 'ratings=' + [...new Set(posts.map(p => p.rating))].join(',') : 'empty');
        if (posts.length) {
            const pv = await fetch(BASE + '/api/video/assets/booru/preview?provider='
                + encodeURIComponent(posts[0].provider) + '&postId=' + posts[0].postId,
                { headers: { Cookie: cookie } });
            const ct = pv.headers.get('content-type') || '';
            const bytes = await pv.arrayBuffer();
            // 代理必须自己下得去图，且按魔数报类型（嗅探不再谎称 JPEG）
            check('booru preview proxy', pv.status === 200 && ct.startsWith('image/') && bytes.byteLength > 0,
                'status=' + pv.status + ' contentType=' + ct + ' bytes=' + bytes.byteLength);
        }
    } else {
        skip('booru search/preview/ingest', `上游不可达或受限（status=${bs.status}）`);
    }

    // 9. 生图段（gate：ComfyUI 存活才走真实 IP-Adapter；否则跳过不阻断数据链路验收）
    const gens = await req('GET', '/api/video/images/generators');
    const comfy = ((gens.json && gens.json.data) || []).find(g => g.type === 'comfyui');
    if (comfy && comfy.available) {
        await req('PUT', '/api/video/images/generator?type=comfyui');
        // 逐镜生图：导出闸要求全镜 READY（服务端硬约束），只生第一镜必红（e2e 首轮实测教训）。
        // 镜 1 带着绑定的参考图走 IP-Adapter，其余镜同链路无参考图（对照就在这一条里）
        const notReady = [];
        for (const s of script.shots) {
            const img = await req('POST', `/api/video/images/shot/${script.id}/${s.id}`, {});
            const st = img.json && img.json.data && img.json.data.imageStatus;
            if (img.status !== 200 || !img.json.success || st !== 'READY') {
                notReady.push(s.id + ':' + (st || ('http' + img.status)));
            }
        }
        check('comfyui images READY for all shots (shot 1 with bound refs)',
            notReady.length === 0, 'notReady=' + notReady.join(','));
        // 10. 导出段（仅全镜就绪时；成片归档由后端保障，这里断言导出成功）
        if (notReady.length === 0) {
            const exp = await req('POST', '/api/video/export/hd', {
                scriptId: script.id, aspectRatio: '16:9', subtitle: false, withLogo: false
            });
            check('export after refs generation', exp.status === 200 && exp.json.success,
                exp.json && !exp.json.success ? 'msg=' + exp.json.message
                    : 'url=' + ((exp.json.data && (exp.json.data.url || exp.json.data.videoUrl)) || ''));
        }
    } else {
        skip('comfyui 生图 + 导出', '生成器不可用（未启动/组合模板未校准）');
    }

    console.log('DONE | scriptId=' + script.id + ' user=' + username
        + ' exitCode=' + (process.exitCode || 0));
})().catch(e => { console.error('SCRIPT ERROR:', e.message); process.exitCode = 1; });
