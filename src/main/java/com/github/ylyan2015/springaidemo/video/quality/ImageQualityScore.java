package com.github.ylyan2015.springaidemo.video.quality;

/**
 * 图片质量评分结果（模块 J）
 * 三个维度 0-10 分，overallScore 为三者均值；
 * llmEvaluated=false 表示来自规则降级评估（仅分辨率启发式，无法判断语义相关性）
 */
public class ImageQualityScore {

    /** 综合得分（三维度均值，0-10） */
    private double overallScore;

    /** 清晰度（分辨率/细节/无模糊伪影） */
    private double clarityScore;

    /** 与提示词的相关性（主体/场景/动作是否贴合） */
    private double relevanceScore;

    /** 美学（构图/光影/色彩） */
    private double aestheticScore;

    /** 改进建议（低分重试时并入负面提示词） */
    private String feedback;

    /** 是否由多模态 LLM 评估（false=规则降级） */
    private boolean llmEvaluated;

    public ImageQualityScore() {}

    // ==================== Getters and Setters ====================

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public double getClarityScore() { return clarityScore; }
    public void setClarityScore(double clarityScore) { this.clarityScore = clarityScore; }

    public double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }

    public double getAestheticScore() { return aestheticScore; }
    public void setAestheticScore(double aestheticScore) { this.aestheticScore = aestheticScore; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }

    public boolean isLlmEvaluated() { return llmEvaluated; }
    public void setLlmEvaluated(boolean llmEvaluated) { this.llmEvaluated = llmEvaluated; }
}