package com.github.ylyan2015.springaidemo.video.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 导演 Agent 服务单元测试（Phase 3 切片2）
 */
class DirectorAgentServiceTest {

    private ModelService modelService;
    private VideoAgentToolFactory toolFactory;
    private SpecialistAgentFactory specialistFactory;
    private UserRepository userRepository;
    private ChatClient baseClient;
    private ChatClient agentClient;
    private DirectorAgentService service;

    /** toolContext 桩捕获的最近一次上下文（供 content 桩模拟闸门写入） */
    private Map<String, Object> lastCtx = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        toolFactory = mock(VideoAgentToolFactory.class);
        specialistFactory = mock(SpecialistAgentFactory.class);
        userRepository = mock(UserRepository.class);
        baseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agentClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(baseClient);
        // 分段 stub：深桩整链注册时的中间调用会被 Mockito 计为实际调用，分段便于 verify 过滤
        ChatClient.Builder builder = baseClient.mutate();
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultToolCallbacks(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(agentClient);
        when(toolFactory.createTools()).thenReturn(List.of());
        when(specialistFactory.createDelegateTools()).thenReturn(List.of());
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call().content()).thenReturn("导演回复");

        service = new DirectorAgentService(modelService, toolFactory, specialistFactory, userRepository, new ObjectMapper());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatReturnsReplyAndSessionId() {
        Map<String, Object> out = service.chat("你好", null, null);
        assertEquals("导演回复", out.get("reply"));
        assertNotNull(out.get("sessionId"));
        assertTrue(((List<?>) out.get("pendingConfirmations")).isEmpty());
    }

    @Test
    void chatRequiresLogin() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.chat("你好", null, null));
        assertTrue(e.getMessage().contains("未登录"));
    }

    @Test
    void blankMessageRejected() {
        assertThrows(RuntimeException.class, () -> service.chat("  ", null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedToolsPassedIntoToolContext() {
        service.chat("确认导出", "s1", List.of("exportVideo"));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(agentClient.prompt(), atLeastOnce()).toolContext(captor.capture());
        Map<String, Object> ctx = captor.getAllValues().stream()
                .filter(m -> m != null && m.containsKey(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY))
                .reduce((a, b) -> b).orElseThrow();
        Object confirmed = ctx.get(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY);
        assertTrue(((java.util.Collection<String>) confirmed).contains("exportVideo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingConfirmationsReturnedFromToolContext() {
        // 模拟闸门在工具执行期写入待确认项：toolContext 桩捕获 map，content 桩向其收集器写入
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenAnswer(inv -> {
            lastCtx = inv.getArgument(0);
            return spec;
        });
        when(spec.call().content()).thenAnswer(inv -> {
            ((List<Map<String, Object>>) lastCtx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY))
                    .add(Map.of("tool", "exportVideo"));
            return "请确认导出";
        });
        Map<String, Object> out = service.chat("导出视频", "s2", null);
        assertEquals("请确认导出", out.get("reply"));
        assertEquals(1, ((List<?>) out.get("pendingConfirmations")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyAccumulatesAcrossCalls() {
        service.chat("第一句", "h1", null);
        service.chat("第二句", "h1", null);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentClient.prompt(), atLeast(2)).messages(captor.capture());
        // 过滤 setUp 桩链注册产生的 null 参数调用，取最后两次真实调用
        List<List<Message>> real = captor.getAllValues().stream().filter(java.util.Objects::nonNull).toList();
        List<Message> secondCall = real.get(real.size() - 1);
        // 第一句 user + 导演回复 assistant + 第二句 user
        assertEquals(3, secondCall.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearHistoryResetsSession() {
        service.chat("第一句", "c1", null);
        service.clearHistory("c1");
        service.chat("新开始", "c1", null);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentClient.prompt(), atLeast(2)).messages(captor.capture());
        List<List<Message>> real = captor.getAllValues().stream().filter(java.util.Objects::nonNull).toList();
        assertEquals(1, real.get(real.size() - 1).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportVideoToolIsGated() {
        ToolCallback raw = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(raw.getToolDefinition().name()).thenReturn("exportVideo");
        when(toolFactory.createTools()).thenReturn(List.of(raw));
        service.chat("导出", "g1", null);
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> tools = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertTrue(tools.get(0) instanceof ConfirmationGateToolCallback);
    }

    @Test
    @SuppressWarnings("unchecked")
    void directorMountsDelegateTools() {
        ToolCallback delegate = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(delegate.getToolDefinition().name()).thenReturn("delegateToScreenwriter");
        when(specialistFactory.createDelegateTools()).thenReturn(List.of(delegate));
        ToolCallback raw = mock(ToolCallback.class, RETURNS_DEEP_STUBS);
        when(raw.getToolDefinition().name()).thenReturn("generateStoryboard");
        when(toolFactory.createTools()).thenReturn(List.of(raw));
        service.chat("写个脚本", "d1", null);
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        verify(baseClient.mutate(), atLeastOnce()).defaultToolCallbacks(captor.capture());
        List<ToolCallback> tools = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "delegateToScreenwriter".equals(t.getToolDefinition().name())));
    }

    // ==================== 代码审查修复回归（m3/n2） ====================

    @Test
    @SuppressWarnings("unchecked")
    void concurrentChatSameSessionKeepsHistoryConsistent() throws Exception {
        // m3：同会话并发 chat 不得丢消息/错乱历史
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenAnswer(inv -> {
            Thread.sleep(10);
            return "回复";
        });
        int n = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
                    service.chat("消息" + idx, "conc", null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        service.chat("收尾", "conc", null);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(spec, atLeastOnce()).messages(captor.capture());
        List<Message> last = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        // n 轮并发（各 user+assistant）+ 收尾 user，共 2n+1 条（上限 20 内）
        assertEquals(2 * n + 1, last.size());
    }

    @Test
    void conversationsMapIsCapped() throws Exception {
        // n2：会话表有上限，超限逐出最旧会话，不无限增长
        for (int i = 0; i < 205; i++) {
            service.chat("你好", "cap-" + i, null);
        }
        java.lang.reflect.Field f = DirectorAgentService.class.getDeclaredField("conversations");
        f.setAccessible(true);
        Map<?, ?> conversations = (Map<?, ?>) f.get(service);
        assertTrue(conversations.size() <= 200, "会话表应受上限约束，实际: " + conversations.size());
    }
}