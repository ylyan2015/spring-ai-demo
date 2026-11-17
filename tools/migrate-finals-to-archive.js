// g4：一次性存量成片归集——把历史 SUCCESS 导出记录的 video_/video_cover_ 文件按脚本标题
// 复制进 uploads/final/（非破坏，日期目录原件保留）。sanitize 规则与 LocalStorageService 一致。
// 用法：node tools/migrate-finals-to-archive.js [--dry-run]
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const FINAL_DIR = path.join(ROOT, 'uploads', 'final');
const CSV = path.join(ROOT, 'target', 'finals.csv');
const DRY = process.argv.includes('--dry-run');

// 与 LocalStorageService.sanitizeFileName 等价：去非法字符与 ..，截断 80
function sanitize(title) {
    if (!title) return '';
    let c = title.replace(/[\\/:*?"<>|]/g, '').replace(/\.\./g, '').trim();
    return c.length > 80 ? c.slice(0, 80) : c;
}
// 解析带引号 CSV（支持 "" 转义）
function parseCsv(text) {
    const rows = []; let row = [], field = '', inQ = false;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (inQ) {
            if (ch === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
            else field += ch;
        } else if (ch === '"') inQ = true;
        else if (ch === ',') { row.push(field); field = ''; }
        else if (ch === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
        else if (ch !== '\r') field += ch;
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }
    return rows;
}
// 目标命名：标题-类别.ext，重名追加时间戳（+同毫秒计数兜底），跨行去重用 set
const usedNames = new Set();
function targetName(base, cat, ext) {
    let name = base + '-' + cat + ext;
    if (fs.existsSync(path.join(FINAL_DIR, name)) || usedNames.has(name)) {
        const ts = Date.now();
        name = base + '-' + cat + '-' + ts + ext;
        let k = 1;
        while (fs.existsSync(path.join(FINAL_DIR, name)) || usedNames.has(name)) {
            name = base + '-' + cat + '-' + ts + '-' + (k++) + ext;
        }
    }
    usedNames.add(name);
    return name;
}

function archiveOne(accessUrl, base, cat, ext) {
    if (!accessUrl || accessUrl.trim() === '') return null;
    const rel = accessUrl.replace(/^\/images\//, '');
    if (rel.startsWith('final/')) return { skip: '已在 final/' };
    const src = path.join(ROOT, 'uploads', rel);
    if (!fs.existsSync(src)) return { skip: '源缺失 ' + rel };
    const name = targetName(base, cat, ext);
    const dst = path.join(FINAL_DIR, name);
    if (!DRY) fs.copyFileSync(src, dst);
    return { copied: rel + '  ->  final/' + name, size: fs.statSync(src).size };
}

const rows = parseCsv(fs.readFileSync(CSV, 'utf8'));
if (!fs.existsSync(FINAL_DIR)) fs.mkdirSync(FINAL_DIR, { recursive: true });
let nVid = 0, nCov = 0, nSkip = 0;
console.log(DRY ? '=== DRY-RUN（不写盘）===' : '=== 执行复制 ===');
for (let i = 1; i < rows.length; i++) {         // [0] 表头
    const [title, videoUrl, coverUrl] = rows[i];
    if (!title && !videoUrl) continue;
    const base = sanitize(title);
    const v = archiveOne(videoUrl, base, '视频', '.mp4');
    if (v && v.copied) { nVid++; console.log('VID  ' + v.copied + ' (' + v.size + 'B)'); }
    else if (v) { nSkip++; console.log('SKIP vid ' + v.skip + ' | ' + title); }
    const c = archiveOne(coverUrl, base, '封面', '.jpg');
    if (c && c.copied) { nCov++; console.log('COV  ' + c.copied + ' (' + c.size + 'B)'); }
    else if (c && c.skip && !c.skip.startsWith('已在')) { /* 无封面常见，静默 */ }
}
console.log(`DONE | 视频归档 ${nVid}，封面归档 ${nCov}，跳过 ${nSkip}`);
