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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 异步编排服务单元测试（Phase 3 切片3）
 * Executor 注入 Runnable::run 使异步路径同步化，断言确定性。
 */
class AgentOrchestrationServiceTest {

    private ModelService modelService;
    private VideoAgentToolFactory toolFactory;
    private SpecialistAgentFactory specialistFactory;
    private UserRepository userRepository;
    private ChatClient baseClient;
    private ChatClient agentClient;
    private AgentOrchestrationService service;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        toolFactory = mock(VideoAgentToolFactory.class);
        specialistFactory = mock(SpecialistAgentFactory.class);
        userRepository = mock(UserRepository.class);
        baseClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        agentClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(modelService.getChatClient()).thenReturn(baseClient);
        ChatClient.Builder builder = baseClient.mutate();
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.defaultToolCallbacks(anyList())).thenReturn(builder);
        when(builder.build()).thenReturn(agentClient);
        when(toolFactory.createTools()).thenReturn(List.of());
        when(specialistFactory.createDelegateTools()).thenReturn(List.of());
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call().content()).thenReturn("流水线完成");

        service = new AgentOrchestrationService(modelService, toolFactory, specialistFactory,
                userRepository, new ObjectMapper(), Runnable::run);

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
    void orchestrateRunsAndCompletes() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        String taskId = (String) out.get("taskId");
        assertNotNull(taskId);
        // 同步 Executor 下工作线程已清理上下文，重设登录态模拟新请求
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        Map<String, Object> status = service.taskStatus(taskId);
        assertEquals("COMPLETED", status.get("status"));
        assertEquals("流水线完成", status.get("reply"));
    }

    @Test
    void securityContextPropagatedToWorkerAndCleared() {
        AtomicReference<String> workerPrincipal = new AtomicReference<>();
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenAnswer(inv -> {
            workerPrincipal.set(SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName() : null);
            return "ok";
        });
        service.orchestrate("猫咪日常", null);
        assertEquals("tester", workerPrincipal.get());
        // 工作线程 finally clearContext：同步 Executor 下当前线程上下文也被清理，
        // 编排不得污染提交线程（真实场景提交线程上下文由请求过滤器管理）
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void orchestrateRequiresLogin() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.orchestrate("x", null));
        assertTrue(e.getMessage().contains("未登录"));
    }

    @Test
    void blankIdeaRejected() {
        assertThrows(RuntimeException.class, () -> service.orchestrate("  ", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gatePausesTaskAndConfirmResumes() {
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenAnswer(inv -> "需要确认导出");
        // 模拟闸门：首次执行写入 pending；confirm 续跑（confirmedTools 已含 exportVideo）无新待确认项
        when(spec.toolContext(anyMap())).thenAnswer(inv -> {
            Map<String, Object> ctx = inv.getArgument(0);
            List<Map<String, Object>> pending =
                    (List<Map<String, Object>>) ctx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY);
            Object confirmed = ctx.get(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY);
            boolean isConfirmed = confirmed instanceof java.util.Collection<?> c && c.contains("exportVideo");
            if (!isConfirmed) {
                pending.add(Map.of("tool", "exportVideo", "input", "{}", "reason", "耗时"));
            }
            return spec;
        });
        Map<String, Object> out = service.orchestrate("猫咪日常并导出", null);
        String taskId = (String) out.get("taskId");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        Map<String, Object> paused = service.taskStatus(taskId);
        assertEquals("AWAITING_CONFIRMATION", paused.get("status"));
        assertEquals(1, ((List<?>) paused.get("pendingConfirmations")).size());

        // confirm 需要提交线程有登录态（上一任务工作线程已清理上下文，重新设置模拟新请求）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        Map<String, Object> resumed = service.confirm(taskId);
        assertEquals("COMPLETED", resumed.get("status"));
    }

    @Test
    void confirmWrongStateRejected() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        // 同步 Executor 下任务已 COMPLETED，且工作线程清理了上下文，重新设置模拟新请求
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.confirm((String) out.get("taskId")));
        assertTrue(e.getMessage().contains("确认"));
    }

    @Test
    void taskOwnershipEnforced() {
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        String taskId = (String) out.get("taskId");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        User other = new User();
        other.setId(2L);
        other.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(other));
        assertThrows(RuntimeException.class, () -> service.taskStatus(taskId));
    }

    @Test
    void failureMarksTaskFailed() {
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.call().content()).thenThrow(new RuntimeException("模型不可用"));
        Map<String, Object> out = service.orchestrate("猫咪日常", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        Map<String, Object> status = service.taskStatus((String) out.get("taskId"));
        assertEquals("FAILED", status.get("status"));
        assertTrue(String.valueOf(status.get("error")).contains("模型不可用"));
    }

    // ==================== 代码审查修复回归（M1/M2/m1/n1） ====================

    @Test
    @SuppressWarnings("unchecked")
    void confirmResumeCarriesOriginalContextAndPendingInput() {
        // M1：续跑必须携带原始创意与待确认操作入参，否则导演无法完成被确认的导出
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.toolContext(anyMap())).thenAnswer(inv -> {
            Map<String, Object> ctx = inv.getArgument(0);
            List<Map<String, Object>> pending =
                    (List<Map<String, Object>>) ctx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY);
            Object confirmed = ctx.get(ConfirmationGateToolCallback.CONFIRMED_TOOLS_KEY);
            boolean isConfirmed = confirmed instanceof java.util.Collection<?> c && c.contains("exportVideo");
            if (!isConfirmed) {
                pending.add(Map.of("tool", "exportVideo",
                        "input", "{\"scriptId\":42,\"preview\":true}", "reason", "耗时"));
            }
            return spec;
        });
        Map<String, Object> out = service.orchestrate("导出脚本42的治愈短片", null);
        String taskId = (String) out.get("taskId");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        service.confirm(taskId);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(spec, atLeastOnce()).messages(captor.capture());
        List<Message> resumeMessages = captor.getAllValues().stream()
                .filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElseThrow();
        String all = resumeMessages.stream().map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("导出脚本42的治愈短片"), "续跑需回放原始任务消息");
        assertTrue(all.contains("42"), "续跑需携带待确认操作的 scriptId");
    }

    @Test
    void concurrentConfirmOnlyOneWins() {
        // M2：状态检查-置位必须原子，并发 confirm 只有一个能提交续跑
        List<Runnable> deferred = new ArrayList<>();
        AgentOrchestrationService deferredService = new AgentOrchestrationService(
                modelService, toolFactory, specialistFactory, userRepository, new ObjectMapper(), deferred::add);
        ChatClient.ChatClientRequestSpec spec = agentClient.prompt();
        when(spec.toolContext(anyMap())).thenAnswer(inv -> {
            Map<String, Object> ctx = inv.getArgument(0);
            List<Map<String, Object>> pending =
                    (List<Map<String, Object>>) ctx.get(ConfirmationGateToolCallback.PENDING_CONFIRMATIONS_KEY);
            pending.add(Map.of("tool", "exportVideo"));
            return spec;
        });
        Map<String, Object> out = deferredService.orchestrate("x", null);
        String taskId = (String) out.get("taskId");
        assertEquals(1, deferred.size());
        deferred.get(0).run(); // 首轮完成 → AWAITING_CONFIRMATION
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        deferredService.confirm(taskId);
        assertThrows(RuntimeException.class, () -> deferredService.confirm(taskId));
        assertEquals(2, deferred.size(), "并发/重复 confirm 只允许一次续跑提交");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictNeverRemovesRunningTask() throws Exception {
        // m1：evict 选中后删除前需复检状态，运行中任务不可被逐出
        Field tasksField = AgentOrchestrationService.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        Map<String, Object> tasks = (Map<String, Object>) tasksField.get(service);
        Class<?> taskClass = Class.forName(
                "com.github.ylyan2015.springaidemo.video.agent.AgentOrchestrationService$OrchestrationTask");
        var ctor = taskClass.getDeclaredConstructor(String.class, Long.class);
        ctor.setAccessible(true);
        Field statusField = taskClass.getDeclaredField("status");
        statusField.setAccessible(true);
        String runningId = null;
        for (int i = 0; i < 100; i++) {
            Object t = ctor.newInstance("fill-" + i, 1L);
            AtomicReference<String> status = (AtomicReference<String>) statusField.get(t);
            if (i == 50) {
                status.set("RUNNING");
                runningId = "fill-" + i;
            } else {
                status.set("COMPLETED");
            }
            tasks.put("fill-" + i, t);
        }
        service.orchestrate("新任务", null);
        assertTrue(tasks.containsKey(runningId), "RUNNING 任务不可被逐出");
        assertEquals(100, tasks.size());
    }

    @Test
    void shutdownStopsOwnedExecutor() throws Exception {
        // n1：容器关闭时优雅终止编排线程池
        ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        AgentOrchestrationService s = new AgentOrchestrationService(
                modelService, toolFactory, specialistFactory, userRepository, new ObjectMapper(), pool);
        s.shutdown();
        assertTrue(pool.isShutdown());
    }
}