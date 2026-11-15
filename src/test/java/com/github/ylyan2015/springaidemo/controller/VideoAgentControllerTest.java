package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.service.BgmService;
import com.github.ylyan2015.springaidemo.service.ImageGenerationService;
import com.github.ylyan2015.springaidemo.service.ImagePromptOptimizerService;
import com.github.ylyan2015.springaidemo.service.StoryScriptService;
import com.github.ylyan2015.springaidemo.service.VideoExportService;
import com.github.ylyan2015.springaidemo.service.VideoTemplateService;
import com.github.ylyan2015.springaidemo.video.agent.AgentOrchestrationService;
import com.github.ylyan2015.springaidemo.video.agent.DirectorAgentService;
import com.github.ylyan2015.springaidemo.video.agent.VideoAgentToolFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 工具内省端点单元测试（Phase 3 切片1）
 */
class VideoAgentControllerTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataOf(ResponseEntity<Map<String, Object>> resp) {
        assertEquals(200, resp.getStatusCode().value());
        assertTrue((Boolean) resp.getBody().get("success"));
        return (List<Map<String, Object>>) resp.getBody().get("data");
    }

    @Test
    void listToolsReturnsNameDescriptionAndSchema() {
        VideoAgentToolFactory factory = mock(VideoAgentToolFactory.class);
        ToolCallback demo = FunctionToolCallback
                .builder("demoTool", (Map<String, Object> in) -> Map.of("ok", true))
                .description("演示工具")
                .inputType(Map.class)
                .build();
        when(factory.createTools()).thenReturn(List.of(demo));

        VideoAgentController controller = new VideoAgentController(factory,
                mock(DirectorAgentService.class), mock(AgentOrchestrationService.class));
        List<Map<String, Object>> items = dataOf(controller.listTools());

        assertEquals(1, items.size());
        assertEquals("demoTool", items.get(0).get("name"));
        assertEquals("演示工具", items.get(0).get("description"));
        assertNotNull(items.get(0).get("inputSchema"));
    }

    @Test
    void listToolsWithRealFactoryReturnsNineTools() {
        // 真实工厂 + 全 mock 服务：验证装配后 9 工具可被内省
        VideoAgentToolFactory factory = new VideoAgentToolFactory(
                mock(StoryScriptService.class),
                mock(VideoTemplateService.class),
                mock(ImagePromptOptimizerService.class),
                mock(ImageGenerationService.class),
                mock(ImageQualityService.class),
                mock(VideoExportService.class),
                mock(BgmService.class),
                mock(com.github.ylyan2015.springaidemo.service.StoryCharacterService.class),
                mock(com.github.ylyan2015.springaidemo.service.VideoGenerationService.class),
                mock(com.github.ylyan2015.springaidemo.service.ShotCandidateService.class));
        VideoAgentController controller = new VideoAgentController(factory,
                mock(DirectorAgentService.class), mock(AgentOrchestrationService.class));
        assertEquals(9, dataOf(controller.listTools()).size());
    }
}