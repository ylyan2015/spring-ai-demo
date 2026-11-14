package com.github.ylyan2015.springaidemo.video.generator;

import java.util.ArrayList;
import java.util.List;

/**
 * 图像尺寸映射工具（OpenAI 兼容 images 协议差异处理）：
 * 各提供者支持的尺寸枚举不同（如 OpenAI gpt-image-1 仅 3 档、智谱 CogView 7 档），
 * 按宽高比（log 差）就近映射到受支持尺寸；支持任意尺寸的提供者（fixedSizes 为空）原样透传。
 */
public final class ImageSizeMapper {

    private ImageSizeMapper() {
    }

    /**
     * 解析逗号分隔的尺寸枚举配置；null/空白 → 空列表（任意尺寸透传）
     */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> sizes = new ArrayList<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                sizes.add(s);
            }
        }
        return sizes;
    }

    /**
     * 将请求尺寸映射到提供者受支持的尺寸：宽高比最接近者胜出（log 比例差最小）
     */
    public static String map(int width, int height, List<String> fixedSizes) {
        if (fixedSizes == null || fixedSizes.isEmpty()) {
            return width + "x" + height;
        }
        double target = Math.log((double) width / height);
        String best = null;
        double bestDiff = Double.MAX_VALUE;
        for (String size : fixedSizes) {
            String[] parts = size.split("x");
            if (parts.length != 2) {
                continue;
            }
            try {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                double diff = Math.abs(Math.log((double) w / h) - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = size;
                }
            } catch (NumberFormatException ignored) {
                // 配置中的非法尺寸条目跳过
            }
        }
        return best != null ? best : width + "x" + height;
    }
}