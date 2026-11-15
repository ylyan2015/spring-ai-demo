# ============================================================
# 预置音效素材生成脚本（Phase 4 切片 4）
# 用 FFmpeg lavfi 合成 4 条免版权环境音效，输出到音效库目录。
# 文件名与 SfxService 的分类对应（category__name.mp3），启动扫描时自动注册。
#
# 用法（FFmpeg 不在 PATH 时指定全路径）：
#   powershell -File tools/generate-preset-sfx.ps1 -FfmpegPath "D:\Program Files\ffmpeg\bin\ffmpeg.exe"
#   powershell -File tools/generate-preset-sfx.ps1 -OutDir ./data/sfx
#
# 说明：合成素材为简单环境音，可用作开箱即用的音效层；
#       追求更好听感时，可将真实免版权音效按 category__name.mp3 命名替换对应文件。
# ============================================================
param(
    [string]$FfmpegPath = "ffmpeg",
    [string]$OutDir = "./data/sfx"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Dur = 6   # 每条音效时长（秒），混音时按镜头起始 adelay 定位

function New-SfxTrack([string]$FileName, [string]$Source, [string]$Filter) {
    Write-Host "Generating $FileName ..."
    $ffArgs = @("-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", $Source)
    if ($Filter) { $ffArgs += @("-af", $Filter) }
    $ffArgs += @("-c:a", "libmp3lame", "-b:a", "128k", (Join-Path $OutDir $FileName))
    & $FfmpegPath @ffArgs
    if ($LASTEXITCODE -ne 0) { throw "FFmpeg failed for $FileName (exit $LASTEXITCODE)" }
}

# ---- wind: 粉噪声低通（柔和风声） ----
New-SfxTrack "wind__soft_breeze.mp3" "anoisesrc=color=pink:amplitude=0.25:d=${Dur}:r=44100" "lowpass=f=400,afade=t=in:st=0:d=1,afade=t=out:st=5:d=1"

# ---- rain: 白噪声高通（细雨沙沙） ----
New-SfxTrack "rain__light_rain.mp3" "anoisesrc=color=white:amplitude=0.2:d=${Dur}:r=44100" "highpass=f=1000,afade=t=in:st=0:d=1,afade=t=out:st=5:d=1"

# ---- footsteps: 低频脉冲衰减（缓慢脚步） ----
New-SfxTrack "footsteps__slow_walk.mp3" "aevalsrc='0.3*sin(2*PI*90*t)*exp(-mod(t,0.6)*8)':d=${Dur}:s=44100:channel_layout=mono" "afade=t=out:st=5:d=1"

# ---- heartbeat: 更低频脉冲（紧张心跳） ----
New-SfxTrack "heartbeat__tense_pulse.mp3" "aevalsrc='0.4*sin(2*PI*55*t)*exp(-mod(t,0.8)*6)':d=${Dur}:s=44100:channel_layout=mono" "afade=t=out:st=5:d=1"

Write-Host ""
Write-Host "Done. 4 preset SFX tracks generated in $OutDir"
Get-ChildItem $OutDir -Filter *.mp3 | ForEach-Object { Write-Host ("  " + $_.Name + "  (" + [math]::Round($_.Length / 1KB) + " KB)") }