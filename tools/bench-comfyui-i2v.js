// Phase 5 干净复测工具：ComfyUI Wan2.2 i2v 单镜基准（手册 §9 基准纪律）
// 前置三件套（调用方负责）：/queue 空 + 无下载/pip + nvidia-smi 无外来 compute 进程
// 用法：node tools/bench-comfyui-i2v.js <frames> [firstFramePng]
// 计时口径：/history status.messages 的 execution_start→execution_success（ComfyUI 执行秒，不含 app 开销）
// seed 固定 42；模板=切片 2 校准版 wan22_i2v_5b.template.json；1280×704@24fps
const fs = require('fs');
const { execFileSync } = require('child_process');
const API = 'http://127.0.0.1:8188';
const frames = parseInt(process.argv[2] || '29', 10);
const firstFrame = process.argv[3] || 'uploads/2026/11/15/463609b9-b0fb-4482-96e0-865e484a5426_comfyui_img_1794755395863_e5962f62-c31a-480a-a176-f72288b9c546.png';
const PROMPT = 'anime scene, school girl brown hair red ribbon uniform, walking slowly in hallway, hair and skirt sway gently, slow camera push-in, stable face, consistent character, smooth motion';

async function jget(path) { return (await fetch(API + path)).json(); }

(async () => {
    const q = await jget('/queue');
    if (q.queue_running.length || q.queue_pending.length) { console.error('ABORT: queue not empty'); process.exit(1); }

    // 上传起点图（multipart，overwrite）
    const fd = new FormData();
    fd.append('image', new Blob([fs.readFileSync(firstFrame)], { type: 'image/png' }), 'bench_first_' + frames + '.png');
    fd.append('overwrite', 'true');
    const up = await (await fetch(API + '/upload/image', { method: 'POST', body: fd })).json();

    // 模板装配（数值不带引号；PROMPT 最后替换）
    let tpl = fs.readFileSync('docs/comfyui/wan22_i2v_5b.template.json', 'utf8');
    const wf = tpl
        .replace('{{FIRST_FRAME}}', JSON.stringify(up.name).slice(1, -1))
        .replace('{{FRAMES}}', String(frames))
        .replace('{{WIDTH}}', '1280')
        .replace('{{HEIGHT}}', '704')
        .replace('{{FPS}}', '24')
        .replace('{{SEED}}', '42')
        .replace('{{PROMPT}}', JSON.stringify(PROMPT).slice(1, -1));
    const sub = await (await fetch(API + '/prompt', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: JSON.parse(wf), client_id: 'bench' }) })).json();
    const pid = sub.prompt_id;
    if (!pid) { console.error('ABORT: no prompt_id', JSON.stringify(sub).slice(0, 300)); process.exit(1); }

    // 轮询至完成
    const deadline = Date.now() + 1800_000;
    let entry = null;
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 3000));
        const h = await jget('/history/' + pid);
        if (h[pid]) {
            const e = h[pid];
            if (e.status && e.status.status_str === 'error') { console.error('ABORT: exec error', JSON.stringify(e.status).slice(0, 400)); process.exit(1); }
            if (e.status && e.status.completed) { entry = e; break; }
        }
    }
    if (!entry) { console.error('ABORT: timeout'); process.exit(1); }

    const ts = {};
    for (const m of entry.status.messages || []) if (Array.isArray(m) && m[1] && m[1].timestamp) ts[m[0]] = m[1].timestamp;
    const elapsed = (ts.execution_success - ts.execution_start) / 1000;

    let out = null;
    for (const node of Object.values(entry.outputs || {})) {
        for (const item of node.images || []) if (item.type === 'output' && item.filename.endsWith('.mp4')) out = item;
    }
    const probe = out ? JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0',
        '-show_entries', 'stream=width,height,nb_frames', '-show_entries', 'format=duration', '-of', 'json',
        'D:/Comfy-Desktop/ComfyUI-Shared/output/' + (out.subfolder ? out.subfolder + '/' : '') + out.filename], { encoding: 'utf8' })) : null;
    console.log(JSON.stringify({ frames, seed: 42, elapsed_s: elapsed, prompt_id: pid,
        output: out && out.filename, probe: probe && { w: probe.streams[0].width, h: probe.streams[0].height,
            nb_frames: probe.streams[0].nb_frames, duration: probe.format.duration } }));
})().catch(e => { console.error('BENCH ERROR:', e.message); process.exit(1); });