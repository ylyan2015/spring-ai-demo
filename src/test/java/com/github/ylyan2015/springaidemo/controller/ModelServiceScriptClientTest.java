package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.repository.ModelParamPresetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脚本生成专用模型覆盖（video.script.model）：仅 deepseek profile 生效——
 * 精修循环使脚本链路 LLM 调用 ×5，单独钉住旗舰模型（deepseek-v4-pro）与更长读超时，
 * 其他对话保持原模型不受牵连；空覆盖/离线 profile 必须回落当前客户端（零行为变化）。
 */
class ModelServiceScriptClientTest {

    private ModelService newService(String activeProfile, String scriptModel) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Environment env = mock(Environment.class);
        // 未打桩的属性读取一律回落到入参默认值
        when(env.getProperty(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(env.getProperty(eq("video.script.model"), anyString())).thenReturn(scriptModel);
        ModelService ms = new ModelService(ctx, env, mock(ModelParamPresetRepository.class));
        ReflectionTestUtils.setField(ms, "activeProfile", activeProfile);
        ms.init();
        return ms;
    }

    @Test
    void resolveScriptModelOnlyOnDeepseekProfile() {
        assertNull(newService("deepseek,h2", "").resolveScriptModel(), "空覆盖=不启用");
        assertEquals("deepseek-v4-pro", newService("deepseek,h2", " deepseek-v4-pro ").resolveScriptModel());
        assertNull(newService("ollama,h2", "deepseek-v4-pro").resolveScriptModel(),
                "离线 profile 忽略云端覆盖，保持本地模型零依赖");
    }

    @Test
    void blankOverrideFallsBackToCurrentClient() {
        ModelService ms = newService("deepseek,h2", "");
        assertSame(ms.getChatClient(), ms.getScriptChatClient(), "未配置覆盖时行为与存量逐字一致");
    }

    @Test
    void overrideBuildsDedicatedCachedClient() {
        ModelService ms = newService("deepseek,h2", "deepseek-v4-pro");
        ChatClient script = ms.getScriptChatClient();
        assertNotNull(script);
        assertNotSame(ms.getChatClient(), script, "覆盖模型必须独立客户端，全局对话不被牵连");
        assertSame(script, ms.getScriptChatClient(), "按模型名缓存，防每次生成重建连接池");
    }
}
