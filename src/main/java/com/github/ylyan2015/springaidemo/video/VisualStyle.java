package com.github.ylyan2015.springaidemo.video;

/**
 * 视觉风格预设枚举
 * 定义常用的图像生成艺术风格，用于提示词优化时指定风格方向
 */
public enum VisualStyle {

    CINEMATIC_REALISM(
            "电影写实",
            "cinematic, photorealistic, film grain, anamorphic lens, dramatic lighting, shallow depth of field, 8k uhd, professional photography",
            "blurry, cartoon, anime, illustration, painting, drawing, sketch, watermark, text, logo, low quality, distorted, deformed"
    ),

    ANIME(
            "日系动漫",
            "anime style, cel shading, vibrant colors, Studio Ghibli inspired, clean lines, detailed background, manga art, high quality animation frame",
            "photorealistic, 3d render, photograph, blurry, low quality, distorted, deformed, watermark, text"
    ),

    WATERCOLOR(
            "水彩插画",
            "watercolor painting, soft edges, pastel colors, artistic, delicate brush strokes, paper texture, gentle gradients, illustration art",
            "photorealistic, 3d render, sharp edges, neon colors, photograph, blurry, low quality, watermark, text"
    ),

    CYBERPUNK(
            "赛博朋克",
            "cyberpunk, neon lights, dark atmosphere, futuristic, rain-soaked streets, holographic signs, high tech low life, blade runner style, dramatic contrast",
            "natural, pastoral, bright, cartoon, cute, blurry, low quality, watermark, text"
    ),

    PIXAR_3D(
            "3D动画",
            "Pixar style 3D render, cute, vibrant, soft lighting, smooth surfaces, expressive characters, colorful, family friendly, high quality CGI animation frame",
            "photorealistic, dark, horror, violent, blurry, low quality, watermark, text, flat, 2d"
    ),

    VINTAGE(
            "复古胶片",
            "vintage film, retro colors, 35mm photography, nostalgic, warm tones, light leaks, film grain, faded colors, old photograph aesthetic",
            "modern, digital, neon, HDR, cartoon, anime, 3d render, blurry, low quality, watermark, text"
    ),

    CHINESE_PAINTING(
            "中国国画",
            "traditional Chinese painting, ink wash, xieyi style, rice paper texture, elegant brushwork, minimalist composition, mountain landscape, poetic atmosphere",
            "photorealistic, 3d render, neon colors, cartoon, anime, western style, blurry, low quality, watermark, text"
    ),

    FANTASY(
            "奇幻史诗",
            "epic fantasy art, magical atmosphere, ethereal glow, mystical landscape, dramatic sky, concept art, highly detailed, digital painting, artstation trending",
            "photograph, realistic photo, mundane, ordinary, blurry, low quality, watermark, text, simple"
    );

    /** 风格中文名称 */
    private final String displayName;

    /** 风格化提示词前缀（英文） */
    private final String promptPrefix;

    /** 负面提示词（英文） */
    private final String negativePrompt;

    VisualStyle(String displayName, String promptPrefix, String negativePrompt) {
        this.displayName = displayName;
        this.promptPrefix = promptPrefix;
        this.negativePrompt = negativePrompt;
    }

    public String getDisplayName() { return displayName; }
    public String getPromptPrefix() { return promptPrefix; }
    public String getNegativePrompt() { return negativePrompt; }

    /**
     * 根据名称查找风格预设（忽略大小写）
     */
    public static VisualStyle fromName(String name) {
        if (name == null || name.isBlank()) {
            return CINEMATIC_REALISM; // 默认风格
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 尝试按中文名称匹配
            for (VisualStyle style : values()) {
                if (style.displayName.equals(name)) {
                    return style;
                }
            }
            return CINEMATIC_REALISM; // 未匹配到则返回默认
        }
    }
}
