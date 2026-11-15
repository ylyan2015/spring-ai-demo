package com.github.ylyan2015.springaidemo.video.generator;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 4 切片 1 真实冒烟（默认跳过）：-Dwan27=true + DASHSCOPE_API_KEY。
 * 同角色 3 镜头参考图出图，目视验收一致性；产物路径打印供人工检查。
 */
@SpringBootTest
class Wan27RealSmokeTest {

    @Autowired
    Wan27ImageGenerator wan27;

    @Value("${spring.ai.dashscope.api-key:}")
    String apiKey;

    @Test
    void sameCharacterThreeShotsWithReference() {
        assumeTrue(Boolean.getBoolean("wan27"), "真实冒烟需 -Dwan27=true");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "需 DASHSCOPE_API_KEY");
        StoryCharacter c = new StoryCharacter();
        c.setName("林晚");
        c.setFeatureTable("long black hair, amber eyes, red wool coat, slim build");
        // 无定妆图时以特征表文本锚定（参考图模式需先上传定妆图，冒烟可替换为真实 URL）
        List<String> refs = List.of();
        for (int i = 1; i <= 3; i++) {
            GeneratedImage img = wan27.generateWithReferences(
                    "Character 林晚: " + c.getFeatureTable() + ". anime style, shot " + i
                            + (i == 1 ? ", close-up, standing under rain" : i == 2 ? ", medium shot, walking in alley" : ", long shot, rooftop at dusk"),
                    null, 1280, 720, refs);
            assertNotNull(img.getImageUrl());
            System.out.println("[wan27-smoke] shot " + i + " -> " + img.getImageUrl());
        }
    }
}