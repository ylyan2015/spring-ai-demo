package com.github.ylyan2015.springaidemo.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * application.yml 脚本精修配置默认值锁定（2026-11-18 成本拍板：v4-pro → flash）：
 * 默认值即真金白银——无声改回旗舰模型不会被任何逻辑测试抓到，token 账单才会告警，太迟。
 * 升档能力必须保留：值仍是 VIDEO_SCRIPT_MODEL 环境变量占位默认，不是写死。
 */
class VideoScriptDefaultsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadAppYaml() throws Exception {
        try (InputStream in = VideoScriptDefaultsTest.class.getClassLoader()
                .getResourceAsStream("application.yml")) {
            assertNotNull(in, "application.yml 应在 classpath");
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void scriptModelDefaultsToFlashWithUpgradePath() throws Exception {
        Map<String, Object> video = (Map<String, Object>) loadAppYaml().get("video");
        Map<String, Object> script = (Map<String, Object>) video.get("script");
        String model = String.valueOf(script.get("model"));
        assertEquals("${VIDEO_SCRIPT_MODEL:deepseek-flash}", model,
                "默认须是 flash（成本档）且保留环境变量升档口子，两个条件缺一即断言失败");
        assertNotNull(script.get("refine-rounds"), "精修轮数默认配置不得被顺手删除");
    }
}
