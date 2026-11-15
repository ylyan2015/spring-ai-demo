package com.github.ylyan2015.springaidemo.video;

/**
 * 角色设计 DTO（Phase 4 切片 1：LLM 结构化输出的人设）
 * featureTable 为英文固定特征表（发型/瞳色/服装/体型），跨镜复用锁定身份
 */
public class CharacterDesign {

    private String name;
    private String featureTable;
    private String personality;
    private String suggestedVoice;

    public CharacterDesign() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFeatureTable() { return featureTable; }
    public void setFeatureTable(String featureTable) { this.featureTable = featureTable; }

    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }

    public String getSuggestedVoice() { return suggestedVoice; }
    public void setSuggestedVoice(String suggestedVoice) { this.suggestedVoice = suggestedVoice; }
}