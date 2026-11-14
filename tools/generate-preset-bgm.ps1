# ============================================================
# 预置 BGM 素材生成脚本（模块 D）
# 用 FFmpeg lavfi 合成 5 首免版权氛围音乐（和弦垫音），输出到 BGM 库目录。
# 文件名与 BgmService.BUILT_IN_PRESETS 的预置清单对应，启动扫描时自动注册并带上 mood 标签。
#
# 用法（FFmpeg 不在 PATH 时指定全路径）：
#   powershell -File tools/generate-preset-bgm.ps1 -FfmpegPath "C:\Program Files\EVCapture\ffmpeg.exe"
#   powershell -File tools/generate-preset-bgm.ps1 -OutDir ./data/bgm
#
# 说明：合成素材为简单和弦垫音，可用作开箱即用的配乐；
#       追求更好听感时，可将真实免版权音乐按 category__name.mp3 命名替换对应文件。
# ============================================================
param(
    [string]$FfmpegPath = "ffmpeg",
    [string]$OutDir = "./data/bgm"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$SegDur = 12        # 每个和弦段时长（秒）
$CrossFade = 2      # 段间交叉淡化（秒）
$TotalDur = 4 * $SegDur - 3 * $CrossFade   # 42s
$FadeOutStart = $TotalDur - 3

# 构建和弦表达式：各频率正弦加权叠加 + 缓慢颤音（避免呆板）
function ChordExpr([double[]]$Freqs, [double[]]$Weights, [double]$TremoloHz = 0.25) {
    $terms = @()
    for ($i = 0; $i -lt $Freqs.Count; $i++) {
        $terms += ("{0}*sin(2*PI*{1}*t)" -f $Weights[$i], $Freqs[$i])
    }
    $sum = $terms -join "+"
    return "($sum)*(0.85+0.15*sin(2*PI*$TremoloHz*t))"
}

# 生成 4 和弦段曲目：aevalsrc x4 → acrossfade 链接 → 整体淡入淡出 → MP3
function New-ChordTrack([string]$FileName, [string[]]$ChordExprs) {
    $ffArgs = @("-y", "-hide_banner", "-loglevel", "error")
    foreach ($expr in $ChordExprs) {
        $ffArgs += @("-f", "lavfi", "-i", "aevalsrc='$expr':d=${SegDur}:s=44100:c=mono")
    }
    $filter = "[0:a][1:a]acrossfade=d=${CrossFade}[a01];" +
              "[a01][2:a]acrossfade=d=${CrossFade}[a012];" +
              "[a012][3:a]acrossfade=d=${CrossFade}[ax];" +
              "[ax]afade=t=in:st=0:d=2,afade=t=out:st=${FadeOutStart}:d=3[final]"
    $ffArgs += @("-filter_complex", $filter, "-map", "[final]",
               "-c:a", "libmp3lame", "-b:a", "128k",
               (Join-Path $OutDir $FileName))
    Write-Host "Generating $FileName ..."
    & $FfmpegPath @ffArgs
    if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed for $FileName (exit $LASTEXITCODE)" }
}

# 权重模板：根音略重，顶部泛音轻，峰值约 0.5 防削波
$w4 = @(0.16, 0.13, 0.12, 0.08)
$w5 = @(0.16, 0.13, 0.11, 0.09, 0.06)

# ---- happy: C - G - Am - F（明亮欢快） ----
New-ChordTrack "happy__sunny_morning.mp3" @(
    (ChordExpr @(261.63, 329.63, 392.00, 523.25) $w4 0.5),
    (ChordExpr @(196.00, 246.94, 293.66, 392.00) $w4 0.5),
    (ChordExpr @(220.00, 261.63, 329.63, 440.00) $w4 0.5),
    (ChordExpr @(174.61, 220.00, 261.63, 349.23) $w4 0.5)
)

# ---- calm: C - Am - F - G（舒缓治愈，低八度为主） ----
New-ChordTrack "calm__peaceful_piano.mp3" @(
    (ChordExpr @(130.81, 196.00, 261.63, 329.63) $w4 0.15),
    (ChordExpr @(110.00, 164.81, 220.00, 261.63) $w4 0.15),
    (ChordExpr @(174.61, 220.00, 261.63, 349.23) $w4 0.15),
    (ChordExpr @(98.00, 196.00, 246.94, 293.66) $w4 0.15)
)

# ---- epic: Dm - Bb - F - C（恢弘史诗，叠加低八度根音） ----
New-ChordTrack "epic__heroic_journey.mp3" @(
    (ChordExpr @(73.42, 146.83, 220.00, 293.66, 349.23) $w5 0.3),
    (ChordExpr @(116.54, 174.61, 233.08, 293.66, 349.23) $w5 0.3),
    (ChordExpr @(87.31, 174.61, 220.00, 261.63, 349.23) $w5 0.3),
    (ChordExpr @(65.41, 130.81, 196.00, 261.63, 329.63) $w5 0.3)
)

# ---- sad: Am - Em - F - Dm（忧伤温情，小三和弦） ----
New-ChordTrack "sad__melancholy_strings.mp3" @(
    (ChordExpr @(110.00, 220.00, 261.63, 329.63) $w4 0.12),
    (ChordExpr @(82.41, 164.81, 246.94, 329.63) $w4 0.12),
    (ChordExpr @(87.31, 174.61, 220.00, 261.63) $w4 0.12),
    (ChordExpr @(73.42, 146.83, 220.00, 293.66) $w4 0.12)
)

# ---- tense: C+Db 不协和持续音 + 2Hz 心跳脉冲（悬疑压迫） ----
$tenseExpr = "(0.20*sin(2*PI*130.81*t)+0.16*sin(2*PI*138.59*t)+0.10*sin(2*PI*65.41*t))*(0.55+0.45*sin(2*PI*2*t))"
Write-Host "Generating tense__suspense_pulse.mp3 ..."
& $FfmpegPath -y -hide_banner -loglevel error `
    -f lavfi -i "aevalsrc='$tenseExpr':d=${TotalDur}:s=44100:c=mono" `
    -af "afade=t=in:st=0:d=2,afade=t=out:st=${FadeOutStart}:d=3" `
    -c:a libmp3lame -b:a 128k (Join-Path $OutDir "tense__suspense_pulse.mp3")
if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed for tense__suspense_pulse.mp3" }

Write-Host ""
Write-Host "Done. 5 preset BGM tracks generated in $OutDir"
Get-ChildItem $OutDir -Filter *.mp3 | ForEach-Object { Write-Host ("  " + $_.Name + "  (" + [math]::Round($_.Length / 1KB) + " KB)") }