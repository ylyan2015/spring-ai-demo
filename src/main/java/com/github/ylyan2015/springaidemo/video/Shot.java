package com.github.ylyan2015.springaidemo.video;

public class Shot {

    private int order;
    private String sceneDescription;
    private String cameraMovement;
    private int durationSeconds;
    private String narration;
    private String imagePrompt;

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
}
