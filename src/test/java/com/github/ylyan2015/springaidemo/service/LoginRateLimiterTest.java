package com.github.ylyan2015.springaidemo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录限速测试（release 审查 M1：防爆破）
 */
class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
    }

    @Test
    void allowsUpToFiveFailuresPerMinute() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("alice", "1.2.3.4"), "第 " + (i + 1) + " 次应放行");
            limiter.recordFailure("alice", "1.2.3.4");
        }
        assertFalse(limiter.tryAcquire("alice", "1.2.3.4"), "第 6 次应拒绝");
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "1.2.3.4");
        }
        assertTrue(limiter.tryAcquire("bob", "1.2.3.4"), "其他用户不受影响");
        assertTrue(limiter.tryAcquire("alice", "5.6.7.8"), "其他 IP 不受影响");
    }

    @Test
    void successClearsFailureCount() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "1.2.3.4");
        }
        limiter.recordSuccess("alice", "1.2.3.4");
        assertTrue(limiter.tryAcquire("alice", "1.2.3.4"));
    }

    @Test
    void expiredFailuresAreForgotten() {
        // 用可注入时钟验证窗口过期：61 秒前的失败不再计数
        long[] now = {1_000_000L};
        LoginRateLimiter timed = new LoginRateLimiter(() -> now[0]);
        for (int i = 0; i < 5; i++) {
            timed.recordFailure("alice", "1.2.3.4");
        }
        assertFalse(timed.tryAcquire("alice", "1.2.3.4"));
        now[0] += 61_000L;
        assertTrue(timed.tryAcquire("alice", "1.2.3.4"), "窗口过期后应恢复");
    }
}