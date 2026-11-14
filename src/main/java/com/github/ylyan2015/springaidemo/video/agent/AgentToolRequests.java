package com.github.ylyan2015.springaidemo.video.agent;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Agent 工具输入参数（Phase 3 切片1，切片3 补充 DelegateTask）
 * 每个 record 对应一个 ToolCallback 的 inputType，@ToolParam 描述会进入 JSON Schema 供 LLM 理解。
 * 可选参数以 null 表达（描述中注明），由工厂做空值兜底。
 */
public final class AgentToolRequests {

    private AgentToolRequests() {}

    /** 生成分镜脚本 */
    public record GenerateStoryboard(
            @ToolParam(description = "创意想法/主题描述，例如：清晨的城市咖啡师，治愈系短片") String idea,
            @ToolParam(description = "可选视频模板ID；提供时按模板风格生成脚本") Long templateId) {}

    /** 提示词优化 */
    public record EnhancePrompt(
            @ToolParam(description = "中文画面/场景描述") String sceneDescription,
            @ToolParam(description = "可选视觉风格名，如 CINEMATIC_REALISM / ANIME / WATERCOLOR") String style) {}

    /** 单镜头图片生成 */
    public record GenerateImage(
            @ToolParam(description = "脚本ID") Long scriptId,
            @ToolParam(description = "镜头ID") Long shotId,
            @ToolParam(description = "可选视觉风格名") String style) {}

    /** 图片质量评估 */
    public record EvaluateImage(
            @ToolParam(description = "图片访问URL") String imageUrl,
            @ToolParam(description = "生成该图片所用的提示词，用于相关性评估") String prompt) {}

    /** 视频合成导出 */
    public record ExportVideo(
            @ToolParam(description = "脚本ID，所有镜头图片须已生成") Long scriptId,
            @ToolParam(description = "true=480p快速预览（带水印），false=高清正式导出，默认 false") Boolean preview,
            @ToolParam(description = "高清分辨率 720p/1080p，默认 720p") String resolution,
            @ToolParam(description = "画面比例 16:9/9:16/1:1，默认 16:9") String aspectRatio,
            @ToolParam(description = "可选BGM的ID") Long bgmId,
            @ToolParam(description = "是否烧录字幕，默认 false") Boolean subtitle,
            @ToolParam(description = "字幕语言 zh/en/bilingual，默认 zh") String subtitleLanguage,
            @ToolParam(description = "是否叠加品牌Logo角标，默认 false") Boolean withLogo) {}

    /** BGM 智能推荐 */
    public record RecommendBgm(
            @ToolParam(description = "视频主题/情感描述，例如：温馨治愈的猫咪日常") String theme) {}

    /** 委托专家 Agent 执行子任务（Phase 3 切片3） */
    public record DelegateTask(
            @ToolParam(description = "交给专家的子任务描述，需包含必要上下文（如脚本ID、镜头ID、主题），专家不共享导演对话历史") String taskDescription) {}
}