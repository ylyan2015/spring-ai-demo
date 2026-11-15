package com.github.ylyan2015.springaidemo.video;

import java.util.List;

public class Storyboard {

    private String title;
    private String theme;
    private List<Shot> shots;
    /** Phase 4 切片 1：先人设后分镜，LLM 单次结构化输出 */
    private List<CharacterDesign> characters;

    public Storyboard() {}

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public List<Shot> getShots() { return shots; }
    public void setShots(List<Shot> shots) { this.shots = shots; }

    public List<CharacterDesign> getCharacters() { return characters; }
    public void setCharacters(List<CharacterDesign> characters) { this.characters = characters; }
}
