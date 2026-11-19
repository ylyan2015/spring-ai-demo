package com.github.ylyan2015.springaidemo.exception;

import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;

import java.util.List;

/**
 * 发布链路上「客户端自己能修」的失败：字段超限、分区未登记、平台未支持、未登录、脚本不属于当前用户。
 *
 * <p>存在的理由是状态码分流：本项目没有全局 {@code @RestControllerAdvice}，每个控制器自己决定
 * 400 还是 500。把这类失败包成本类型后，控制器只需一条规则——本类型 → 400 并把 message 原文回显
 * （用户看得懂、也改得动），其余 RuntimeException → 500 且只回固定文案（内部原文含表名列名，
 * 回显等于把库结构送给陌生人）。
 *
 * <p>{@code violations} 只在字段级校验失败时非空，前端据此把红框点到具体输入框上。
 */
public class PublishRejectException extends IllegalArgumentException {

    private final List<PublishSpec.Violation> violations;

    public PublishRejectException(String message) {
        this(message, List.of());
    }

    public PublishRejectException(String message, List<PublishSpec.Violation> violations) {
        super(message);
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public List<PublishSpec.Violation> violations() {
        return violations;
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}
