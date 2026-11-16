package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 唯一 prompt 装配点（Phase 4 切片 1）
 * finalPrompt = 风格前缀 + 角色特征表（逐角色） + 镜头提示词/场景描述 + 景别/运镜补充。
 * ImageGenerationService 与提示词优化器统一调用，禁止多处各自拼接。
 */
@Component
public class ShotPromptAssembler {

    /**
     * @param shot       镜头实体
     * @param style      视觉风格（调用方已处理脚本级 style 回退）
     * @param characters 出场角色卡（可 null/空 → 纯文本降级）
     */
    public String assemble(StoryShot shot, VisualStyle style, List<StoryCharacter> characters) {
        String base = shot.getImagePrompt();
        if (base == null || base.isBlank()) {
            base = shot.getSceneDescription();
        }
        if (base == null || base.isBlank()) {
            throw new RuntimeException("镜头 " + shot.getShotOrder() + " 没有可用的提示词或场景描述");
        }
        StringBuilder sb = new StringBuilder(style.getPromptPrefix());
        if (characters != null) {
            for (StoryCharacter c : characters) {
                if (c.getFeatureTable() != null && !c.getFeatureTable().isBlank()) {
                    sb.append(", Character ").append(c.getName()).append(": ")
                            .append(c.getFeatureTable()).append('.');
                }
                // Phase 5 切片 3：LoRA 触发词注入（仅非空；无触发词输出逐字不变）
                if (c.getTriggerWord() != null && !c.getTriggerWord().isBlank()) {
                    sb.append(", ").append(c.getTriggerWord());
                }
            }
        }
        sb.append(", ").append(base);
        sb.append(", ").append(shotSizeEnglish(shot.getShotSize()));
        if (shot.getCameraMovement() != null && !shot.getCameraMovement().isBlank()) {
            sb.append(", camera: ").append(shot.getCameraMovement());
        }
        return sb.toString();
    }

    /** 景别英文映射：CLOSE→close-up、MEDIUM→medium shot、LONG→long shot */
    private static String shotSizeEnglish(String size) {
        if (size == null) {
            return "medium shot";
        }
        return switch (size) {
            case "CLOSE" -> "close-up";
            case "LONG" -> "long shot";
            default -> "medium shot";
        };
    }
}