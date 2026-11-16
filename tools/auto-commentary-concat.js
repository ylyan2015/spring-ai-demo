// tools/auto-commentary-concat.js
// Phase 5 Track B Task 0：评论类脚本级自动拼接（产品外工具，先行发片）
// 用法：node tools/auto-commentary-concat.js --script <json> --main <mp4> --assets <dir> --out <mp4> [--dry-run]
// 素材约定文件名 insert-<id>.mp4|png；缺失素材跳过并 warn（该镜保留主体画面）
const fs = require('fs'); const path = require('path');
const { execFileSync } = require('child_process');
const XFADE = 0.8;
function shotStarts(shots) {
  let acc = 0; return shots.map((s, i) => { const st = acc - i * XFADE; acc += s.durationSeconds; return Math.max(0, +st.toFixed(2)); });
}
function esc(t) { return t.replace(/\\/g, '\\\\').replace(/:/g, '\\:').replace(/'/g, "\\'"); }
function buildCommand({ main, out, width, height, inserts }) {
  const cmd = ['ffmpeg', '-y', '-i', main];
  const filters = []; let prev = '[0:v]';
  inserts.forEach((ins, k) => {
    if (ins.isImage) cmd.push('-loop', '1');
    cmd.push('-i', ins.file);
    const idx = k + 1;
    // fontfile 显式指定微软雅黑：本机 fontconfig 缺省配置缺失，不指定则 drawtext 静默不画字（出处水印=版权护栏，必须真实渲染）
    filters.push(`[${idx}:v]trim=duration=${ins.seconds},setpts=PTS-STARTPTS,fps=30,` +
      `scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height},` +
      `drawtext=fontfile='C\\:/Windows/Fonts/msyh.ttc':text='${esc(ins.attribution)}':fontsize=${Math.max(16, height / 30)}:fontcolor=white@0.85:` +
      `x=w-tw-${Math.round(height / 40)}:y=h-th-${Math.round(height / 40)}[ins${k}]`);
    const end = +(ins.start + ins.seconds).toFixed(2);
    const label = k === inserts.length - 1 ? '[vout]' : `[ov${k}]`;
    filters.push(`${prev}[ins${k}]overlay=enable='between(t,${ins.start.toFixed(2)},${end.toFixed(2)})'${label}`);
    prev = label;
  });
  if (inserts.length) {
    cmd.push('-filter_complex', filters.join(';'), '-map', '[vout]', '-map', '0:a');
  } else {
    cmd.push('-map', '0:v', '-map', '0:a');
  }
  cmd.push('-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', '-c:a', 'copy', '-movflags', '+faststart', out);
  return cmd;
}
function main() {
  const a = process.argv.slice(2); const get = k => a[a.indexOf(k) + 1];
  const script = JSON.parse(fs.readFileSync(get('--script'), 'utf8'));
  const assets = get('--assets'); const dry = a.includes('--dry-run');
  const starts = shotStarts(script.shots);
  const inserts = [];
  script.shots.forEach((s, i) => {
    if (!s.insert) return;
    const mp4 = path.join(assets, `insert-${s.insert.id}.mp4`);
    const png = path.join(assets, `insert-${s.insert.id}.png`);
    const file = fs.existsSync(mp4) ? mp4 : fs.existsSync(png) ? png : null;
    if (!file) { console.warn(`WARN insert ${s.insert.id} missing, skip`); return; }
    inserts.push({ id: s.insert.id, file, start: starts[i], seconds: s.insert.seconds,
      attribution: s.insert.attribution, isImage: file.endsWith('.png') });
  });
  const cmd = buildCommand({ main: get('--main'), out: get('--out'),
    width: script.aspectRatio === '9:16' ? 720 : 1280, height: script.aspectRatio === '9:16' ? 1280 : 720, inserts });
  if (dry) { console.log(cmd.join(' ')); return; }
  execFileSync(cmd[0], cmd.slice(1), { stdio: ['ignore', 'ignore', 'inherit'] });
  console.log('CONCAT_OK ' + get('--out'));
}
if (require.main === module) main();
module.exports = { shotStarts, buildCommand };