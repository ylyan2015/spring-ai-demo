package com.github.ylyan2015.springaidemo.controller;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import com.github.ylyan2015.springaidemo.exception.PublishRejectException;
import com.github.ylyan2015.springaidemo.service.PublishMetaDeriver;
import com.github.ylyan2015.springaidemo.service.PublishMetaLlmService;
import com.github.ylyan2015.springaidemo.service.PublishMetaService;
import com.github.ylyan2015.springaidemo.video.publish.PublishSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布元数据端点：规格表 / 派生草稿 / 保存 / 读取。目标只有一个——
 * 让用户在 B 站投稿页上逐项粘贴时不用回头翻脚本，也不用猜平台限制。
 *
 * <p>鉴权沿用本项目口径：{@code /api/video/**} 在 SecurityConfig 里 permitAll，
 * 登录与脚本归属由服务层 {@code getCurrentUserId()} 兜，这里不加 {@code @PreAuthorize}。
 *
 * <p>状态码分流（本项目没有全局 advice，每个控制器自己判）：
 * <ul>
 *   <li>{@link PublishRejectException} → 400 + message 原文（用户改得动的失败）+ 结构化 violations</li>
 *   <li>其他 {@link IllegalArgumentException} → 400 + message（如未登记的分区名）</li>
 *   <li>其余 {@link RuntimeException} → 500 + 固定文案，原文只进日志（含表名列名，回显等于送库结构）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/video/publish")
public class PublishMetaController {

    private static final Logger log = LoggerFactory.getLogger(PublishMetaController.class);

    /**
     * 灰度期兜底口径（复核 2026-11-19）：分区随机分配或二级分区下线时，投稿地址带 {@code ?tid=} 指定分区。
     * 由服务端下发而不是写进 JS，规则变了只改 {@link PublishSpec} 一处。
     */
    private static final String TID_HINT =
            "若投稿页分区下拉里找不到所选分区，可在提交地址后加 ?tid=<分区号> 指定（B 站分区灰度期口径，复核 2026-11-19）";

    private final PublishMetaService metaService;
    private final PublishMetaDeriver deriver;
    private final PublishMetaLlmService llmService;

    public PublishMetaController(PublishMetaService metaService,
                                 PublishMetaDeriver deriver,
                                 PublishMetaLlmService llmService) {
        this.metaService = metaService;
        this.deriver = deriver;
        this.llmService = llmService;
    }

    /** 平台规格表：前端计数器、分区下拉、声明候选、禁用词提示全部以此为源 */
    @GetMapping("/spec")
    public ResponseEntity<Map<String, Object>> spec(@RequestParam(defaultValue = PublishSpec.PLATFORM_BILIBILI) String platform) {
        try {
            PublishSpec s = PublishMetaService.specFor(platform);
            List<Map<String, Object>> cats = new ArrayList<>();
            for (PublishSpec.Category c : s.categories()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", c.name());
                one.put("tid", c.tid());
                one.put("descMax", c.descMax());
                cats.add(one);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("platform", s.platform());
            data.put("titleMax", s.titleMax());
            data.put("tagMax", s.tagMax());
            data.put("descMax", s.descMax());
            data.put("categories", cats);
            data.put("declarationOptions", s.declarationOptions());
            data.put("coverNote", s.coverNote());
            data.put("bannedWords", PublishSpec.BANNED_WORDS);
            data.put("tidHint", TID_HINT);
            return ok(data);
        } catch (PublishRejectException e) {
            return reject(e);
        } catch (IllegalArgumentException e) {
            return reject(e);
        } catch (RuntimeException e) {
            return internal(e, "spec");
        }
    }

    /**
     * 派生草稿：确定性装配（事实/出处/时间戳）+ 可选 LLM 标题候选与钩子。
     *
     * @param llm 关掉即零模型调用（省钱开关；{@code /meta} 的回退路径恒为关）
     */
    @GetMapping("/{scriptId}/draft")
    public ResponseEntity<Map<String, Object>> draft(@PathVariable Long scriptId,
                                                     @RequestParam(defaultValue = PublishSpec.PLATFORM_BILIBILI) String platform,
                                                     @RequestParam(defaultValue = "true") boolean llm) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(deriver.derive(scriptId, platform));
            data.put("titleCandidates", List.of());
            data.put("hook", null);
            if (llm) {
                data.put("titleCandidates", llmService.generateTitles(scriptId, platform));
                data.put("hook", llmService.generateHook(scriptId, platform));
            }
            return ok(data);
        } catch (PublishRejectException e) {
            return reject(e);
        } catch (IllegalArgumentException e) {
            return reject(e);
        } catch (RuntimeException e) {
            return internal(e, "draft");
        }
    }

    /** 人工保存：只带改动字段即可（部分更新语义见 {@link PublishMetaService#save}） */
    @PutMapping("/{scriptId}/meta")
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long scriptId,
                                                   @RequestParam(defaultValue = PublishSpec.PLATFORM_BILIBILI) String platform,
                                                   @RequestBody Map<String, Object> form) {
        try {
            PublishMeta saved = metaService.save(scriptId, platform, form);
            return ok(metaService.toMap(saved));
        } catch (PublishRejectException e) {
            return reject(e);
        } catch (IllegalArgumentException e) {
            return reject(e);
        } catch (RuntimeException e) {
            return internal(e, "save");
        }
    }

    /** 读已存值；没人存过就回确定性草稿（不碰模型：这是面板一打开就会打的读接口） */
    @GetMapping("/{scriptId}/meta")
    public ResponseEntity<Map<String, Object>> meta(@PathVariable Long scriptId,
                                                    @RequestParam(defaultValue = PublishSpec.PLATFORM_BILIBILI) String platform) {
        try {
            Optional<PublishMeta> stored = metaService.find(scriptId, platform);
            Map<String, Object> data = new LinkedHashMap<>();
            if (stored.isPresent()) {
                data.put("fallbackToDraft", false);
                data.putAll(metaService.toMap(stored.get()));
            } else {
                data.put("fallbackToDraft", true);
                data.putAll(deriver.derive(scriptId, platform));
            }
            return ok(data);
        } catch (PublishRejectException e) {
            return reject(e);
        } catch (IllegalArgumentException e) {
            return reject(e);
        } catch (RuntimeException e) {
            return internal(e, "meta");
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** 400：message 原文可回显（都是用户看得懂的文案）；字段级违规另附 violations 供前端定位输入框 */
    private static ResponseEntity<Map<String, Object>> reject(Exception e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        if (e instanceof PublishRejectException pre && pre.hasViolations()) {
            List<Map<String, Object>> vs = new ArrayList<>();
            for (PublishSpec.Violation v : pre.violations()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("field", v.field());
                one.put("code", v.code());
                one.put("message", v.message());
                vs.add(one);
            }
            body.put("violations", vs);
        }
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<Map<String, Object>> internal(RuntimeException e, String endpoint) {
        log.error("Publish endpoint {} failed", endpoint, e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", "发布元数据操作失败，请查看服务端日志");
        return ResponseEntity.internalServerError().body(body);
    }
}
