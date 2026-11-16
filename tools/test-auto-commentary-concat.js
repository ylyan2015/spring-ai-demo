// tools/test-auto-commentary-concat.js
const assert = require('assert');
const { shotStarts, buildCommand } = require('./auto-commentary-concat.js');
const shots = [
  { durationSeconds: 8, insert: null },
  { durationSeconds: 8, insert: { id: 'A', seconds: 3, attribution: '第1话 · 评论引用' } },
  { durationSeconds: 10, insert: { id: 'B', seconds: 3, attribution: '第4话 · 评论引用' } }
];
// xfade=0.8：start_i = sum(dur[0..i-1]) - i*0.8
assert.deepStrictEqual(shotStarts(shots), [0, 7.2, 14.4]);
const cmd = buildCommand({
  main: 'main.mp4', out: 'out.mp4', width: 1280, height: 720,
  inserts: [{ id: 'A', file: 'insert-A.mp4', start: 7.2, seconds: 3, attribution: '第1话 · 评论引用', isImage: false }]
});
const s = cmd.join(' ');
assert.ok(s.includes("overlay=enable='between(t,7.20,10.20)'"));
assert.ok(s.includes('drawtext='));
// 出处水印必须真实渲染：本机 fontconfig 缺省配置缺失，drawtext 不显式指定 fontfile 会静默不画字（冒烟实证）
assert.ok(s.includes('fontfile='));
// 盘符冒号须单引号包裹+\: 转义（filtergraph 与选项两级解析，裸 \: 会被图级吃掉致选项拆分报错）
assert.ok(s.includes("fontfile='C\\:/Windows/Fonts/msyh.ttc'"));
assert.ok(s.includes('-c:a copy')); // 主体音轨（解说/BGM/字幕烧录已完成）不动
assert.ok(!s.includes('insert-B')); // 未提供素材不进入命令
console.log('TEST_OK');