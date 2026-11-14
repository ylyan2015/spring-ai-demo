package com.github.ylyan2015.springaidemo.video.generator;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地模型风格预设（模块 G 收尾）
 * 按 image.local.model 模型名匹配内置的风格前缀与负面提示词，
 * 也可通过 image.local.style-prefix / image.local.extra-negative-prompt 配置覆盖。
 * 仅作用于本地 SD WebUI 协议生成器（云端 DashScope 不支持负面提示词，不套用）。
 */
public final class LocalModelStylePreset {

    /** SD 1.5 系通用质量前缀 */
    private static final String SD15_PREFIX =
            "masterpiece, best quality, highly detailed, sharp focus";
    /** SD 1.5 系通用负面词 */
    private static final String SD15_NEGATIVE =
            "lowres, bad anatomy, bad hands, extra fingers, missing fingers, blurry, "
                    + "watermark, text, signature, jpeg artifacts, cropped, worst quality, low quality";

    /** SDXL 系质量前缀 */
    private static final String SDXL_PREFIX =
            "masterpiece, best quality, ultra detailed, professional photography, sharp focus";
    /** SDXL 系负面词 */
    private static final String SDXL_NEGATIVE =
            "lowres, bad anatomy, bad hands, extra digits, fewer digits, blurry, "
                    + "watermark, text, signature, worst quality, low quality, deformed";

    /** FLUX 系前缀（FLUX 对负面提示词不敏感，负面词留空） */
    private static final String FLUX_PREFIX =
            "FLUX style, high quality, detailed, cinematic composition";

    private final String stylePrefix;
    private final String negativePrompt;

    private LocalModelStylePreset(String stylePrefix, String negativePrompt) {
        this.stylePrefix = stylePrefix;
        this.negativePrompt = negativePrompt;
    }

    /**
     * 解析风格预设：配置覆盖优先，其次按模型名匹配内置预设，未知模型回退 SD 1.5 通用预设
     *
     * @param modelName         本地模型名称（image.local.model）
     * @param configPrefix      配置的风格前缀覆盖（image.local.style-prefix，空白表示不覆盖）
     * @param configNegative    配置的负面词覆盖（image.local.extra-negative-prompt，空白表示不覆盖）
     */
    public static LocalModelStylePreset resolve(String modelName, String configPrefix, String configNegative) {
        String name = modelName == null ? "" : modelName.toLowerCase();

        String builtInPrefix;
        String builtInNegative;
        if (name.contains("flux")) {
            builtInPrefix = FLUX_PREFIX;
            builtInNegative = "";
        } else if (name.contains("sdxl") || name.contains("sd-xl") || name.contains("xl")) {
            builtInPrefix = SDXL_PREFIX;
            builtInNegative = SDXL_NEGATIVE;
        } else {
            // stable-diffusion / sd1.5 / 未知模型统一回退通用预设
            builtInPrefix = SD15_PREFIX;
            builtInNegative = SD15_NEGATIVE;
        }

        String prefix = configPrefix != null && !configPrefix.isBlank() ? configPrefix.trim() : builtInPrefix;
        String negative = configNegative != null && !configNegative.isBlank() ? configNegative.trim() : builtInNegative;
        return new LocalModelStylePreset(prefix, negative);
    }

    /** 将风格前缀拼接到提示词之前（前缀为空时原样返回） */
    public static String composePrompt(String stylePrefix, String prompt) {
        if (stylePrefix == null || stylePrefix.isBlank()) {
            return prompt;
        }
        return stylePrefix.trim() + ", " + prompt;
    }

    /** 合并两组负面词（逗号分隔、去重、保持顺序；空输入安全） */
    public static String mergeNegative(String first, String second) {
        Set<String> terms = new LinkedHashSet<>();
        for (String part : new String[]{first, second}) {
            if (part == null || part.isBlank()) {
                continue;
            }
            for (String term : part.split(",")) {
                String trimmed = term.trim();
                if (!trimmed.isEmpty()) {
                    terms.add(trimmed);
                }
            }
        }
        return String.join(", ", terms);
    }

    public String getStylePrefix() {
        return stylePrefix;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }
}