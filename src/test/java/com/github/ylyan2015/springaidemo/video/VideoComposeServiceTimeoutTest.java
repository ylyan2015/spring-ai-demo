package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 外部进程超时回归测试（release 审查 M8：输出阻塞读导致 waitFor 超时永不生效）
 * 用真实挂起进程验证：超时必须生效并强杀进程，而不是永久阻塞请求线程。
 */
class VideoComposeServiceTimeoutTest {

    private Path workDir;

    @BeforeEach
    void windowsOnly() throws IOException {
        // 挂起/退出进程用 Windows 专属命令（ping -n / cmd /c）；其他平台跳过（release 复审 n-2）
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "超时回归用例使用 Windows 命令，非 Windows 平台跳过");
        workDir = Files.createTempDirectory("compose-timeout-");
    }

    /**
     * 不用 @TempDir 自动清理：被强杀进程的日志句柄在 Windows 上会短暂不释放，
     * JUnit 删不掉目录就报「Failed to close extension context」，与本用例要验的超时逻辑无关却随机弄红门禁。
     * 本用例的判据全在超时与退出码上，目录回收交给系统。
     */
    @AfterEach
    void cleanWorkDir() {
        if (workDir == null) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 句柄未释放就放弃，不抛
                }
            });
        } catch (IOException ignored) {
        }
    }

    private VideoComposeService newServiceWithTimeout(Duration timeout) throws Exception {
        VideoComposeService service = new VideoComposeService(mock(StorageService.class));
        Field f = VideoComposeService.class.getDeclaredField("commandTimeout");
        f.setAccessible(true);
        f.set(service, timeout);
        return service;
    }

    private void execute(VideoComposeService service, List<String> command) throws Exception {
        Method m = VideoComposeService.class.getDeclaredMethod(
                "executeCommand", List.class, Path.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(service, command, workDir, "test-process");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    @Test
    void hangingProcessIsKilledAfterTimeout() throws Exception {
        VideoComposeService service = newServiceWithTimeout(Duration.ofSeconds(1));
        // ping 30 次 ≈ 30 秒挂起；旧实现会阻塞读输出直到进程结束（超时形同虚设）
        long start = System.currentTimeMillis();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> execute(service, List.of("ping", "-n", "30", "127.0.0.1")));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(e.getMessage().contains("timed out"), "应报超时: " + e.getMessage());
        assertTrue(elapsed < 15_000, "超时必须及时生效，实际耗时 " + elapsed + "ms");
    }

    @Test
    void normalProcessStillSucceeds() throws Exception {
        VideoComposeService service = newServiceWithTimeout(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> execute(service, List.of("cmd", "/c", "exit", "0")));
    }

    @Test
    void failingProcessReportsExitCode() throws Exception {
        VideoComposeService service = newServiceWithTimeout(Duration.ofSeconds(30));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> execute(service, List.of("cmd", "/c", "exit", "3")));
        assertTrue(e.getMessage().contains("exit 3"));
    }
}