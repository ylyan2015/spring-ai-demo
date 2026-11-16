package com.github.ylyan2015.springaidemo.video.generator;

/**
 * LoRA 生成规格（Phase 5 切片 3）
 * loraName=角色 LoRA 文件名；triggerWord 经 ShotPromptAssembler 注入 prompt；
 * controlNetRefUrl=定妆参考图（仅 controlnet-enabled 时携带，供 canny 锁机位）。
 */
public record LoraSpec(String loraName, String triggerWord, String controlNetRefUrl) {
}