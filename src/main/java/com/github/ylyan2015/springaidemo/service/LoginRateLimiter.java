package com.github.ylyan2015.springaidemo.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 登录限速器（release 审查 M1：防爆破）
 * 按 用户名+IP 滑动窗口计数：60 秒内最多 5 次失败，超限拒绝；登录成功清零。
 * 内存态（重启清空），与项目其他内存态设计一致。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MILLIS = 60_000L;
    /** 键数量软上限：防伪造海量用户名/IP 撑爆内存，超限时整体清空（最坏情况限速短暂失效，可接受） */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginRateLimiter() {
        this(System::currentTimeMillis);
    }

    /** 可注入时钟便于测试窗口过期 */
    LoginRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** 是否允许再次尝试（不计数） */
    public boolean tryAcquire(String username, String ip) {
        Deque<Long> window = failures.get(key(username, ip));
        if (window == null) {
            return true;
        }
        synchronized (window) {
            prune(window);
            return window.size() < MAX_FAILURES;
        }
    }

    /** 记录一次失败（验证码错误/密码错误均计入） */
    public void recordFailure(String username, String ip) {
        if (failures.size() > MAX_KEYS) {
            failures.clear();
        }
        Deque<Long> window = failures.computeIfAbsent(key(username, ip), k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(clock.getAsLong());
            prune(window);
        }
    }

    /** 登录成功清零 */
    public void recordSuccess(String username, String ip) {
        failures.remove(key(username, ip));
    }

    private void prune(Deque<Long> window) {
        long cutoff = clock.getAsLong() - WINDOW_MILLIS;
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
    }

    private String key(String username, String ip) {
        return (username == null ? "" : username.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }
}