package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LoRA 解析器（Phase 5 切片 3）
 * 取首个带 LoRA 的角色卡（单 LoRA 身份方案，实测 IPAdapter 不迁身份已撤销）；
 * ControlNet 参考图 gated：image.comfyui.controlnet-enabled 关闭时不携带。
 */
@Component
public class LoraResolver {

    private final boolean controlnetEnabled;

    public LoraResolver(@Value("${image.comfyui.controlnet-enabled:false}") boolean controlnetEnabled) {
        this.controlnetEnabled = controlnetEnabled;
    }

    /** 无 LoRA 角色 → null（调用方走存量生成路径，行为逐字不变） */
    public LoraSpec resolve(List<StoryCharacter> characters) {
        if (characters == null) {
            return null;
        }
        for (StoryCharacter c : characters) {
            if (c.getLoraName() != null && !c.getLoraName().isBlank()) {
                return new LoraSpec(c.getLoraName(), c.getTriggerWord(),
                        controlnetEnabled ? c.getReferenceImageUrl() : null);
            }
        }
        return null;
    }
}