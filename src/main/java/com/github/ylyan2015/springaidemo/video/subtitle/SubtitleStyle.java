package com.github.ylyan2015.springaidemo.video.subtitle;

/**
 * 字幕样式配置（烧录为 ASS 字幕的样式参数）
 * 字号基于 1280x720 虚拟坐标系（ASS PlayResX/Y），实际输出时 libass 自动等比缩放
 */
public class SubtitleStyle {

    /** 字体名称（需系统已安装，如 Microsoft YaHei / Source Han Sans CN） */
    private String fontName = "Microsoft YaHei";

    /** 字号（1280x720 坐标系下的像素，默认 24） */
    private int fontSize = 24;

    /** 字体颜色（#RRGGBB，默认白色） */
    private String fontColor = "#FFFFFF";

    /** 描边颜色（#RRGGBB，默认黑色） */
    private String outlineColor = "#000000";

    /** 描边宽度（像素，默认 2） */
    private int outlineWidth = 2;

    /** 显示位置: bottom / center / top */
    private String position = "bottom";

    public SubtitleStyle() {}

    public SubtitleStyle fontName(String fontName) { this.fontName = fontName; return this; }
    public SubtitleStyle fontSize(int fontSize) { this.fontSize = fontSize; return this; }
    public SubtitleStyle fontColor(String fontColor) { this.fontColor = fontColor; return this; }
    public SubtitleStyle outlineColor(String outlineColor) { this.outlineColor = outlineColor; return this; }
    public SubtitleStyle outlineWidth(int outlineWidth) { this.outlineWidth = outlineWidth; return this; }
    public SubtitleStyle position(String position) { this.position = position; return this; }

    public String getFontName() { return fontName; }
    public int getFontSize() { return fontSize; }
    public String getFontColor() { return fontColor; }
    public String getOutlineColor() { return outlineColor; }
    public int getOutlineWidth() { return outlineWidth; }
    public String getPosition() { return position; }

    /** 位置是否合法（bottom / center / top，大小写不敏感） */
    public boolean isValidPosition() {
        return position != null
                && (position.equalsIgnoreCase("bottom")
                || position.equalsIgnoreCase("center")
                || position.equalsIgnoreCase("top"));
    }
}
