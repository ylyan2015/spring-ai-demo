package com.github.ylyan2015.springaidemo.video;

public class Shot {

    private int order;
    private String sceneDescription;
    private String cameraMovement;
    private int durationSeconds;
    private String narration;
    private String imagePrompt;
    /** Phase 4 切片 1：出场角色名（持久化时 name→id 关联） */
    private java.util.List<String> characterNames;
    /** Phase 4 切片 1：景别 CLOSE/MEDIUM/LONG */
    private String shotSize;
    /** Phase 4 切片 4：本镜角色对白（中文，无对白为空串） */
    private String dialogue;
    /** Phase 4 切片 4：对白角色名（持久化时 name→id 关联） */
    private String dialogueCharacterName;

    public Shot() {}

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    public String getSceneDescription() { return sceneDescription; }
    public void setSceneDescription(String sceneDescription) { this.sceneDescription = sceneDescription; }

    public String getCameraMovement() { return cameraMovement; }
    public void setCameraMovement(String cameraMovement) { this.cameraMovement = cameraMovement; }

    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }

    public String getImagePrompt() { return imagePrompt; }
    public void setImagePrompt(String imagePrompt) { this.imagePrompt = imagePrompt; }

    public java.util.List<String> getCharacterNames() { return characterNames; }
    public void setCharacterNames(java.util.List<String> characterNames) { this.characterNames = characterNames; }

    public String getShotSize() { return shotSize; }
    public void setShotSize(String shotSize) { this.shotSize = shotSize; }

    public String getDialogue() { return dialogue; }
    public void setDialogue(String dialogue) { this.dialogue = dialogue; }

    public String getDialogueCharacterName() { return dialogueCharacterName; }
    public void setDialogueCharacterName(String dialogueCharacterName) { this.dialogueCharacterName = dialogueCharacterName; }
}
