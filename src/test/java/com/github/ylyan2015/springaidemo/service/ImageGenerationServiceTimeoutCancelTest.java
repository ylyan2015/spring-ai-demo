package com.github.ylyan2015.springaidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.StoryShot;
import com.github.ylyan2015.springaidemo.repository.StoryScriptRepository;
import com.github.ylyan2015.springaidemo.repository.StoryShotRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.ImageGenerationProgress;
import com.github.ylyan2015.springaidemo.video.VisualStyle;
import com.github.ylyan2015.springaidemo.video.generator.GeneratedImage;
import com.github.ylyan2015.springaidemo.video.generator.ImageGenerator;
import com.github.ylyan2015.springaidemo.video.generator.ImageGeneratorFactory;
import com.github.ylyan2015.springaidemo.video.quality.ImageQualityService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量生图超时取消测试（release 复审 m-2）：
 * CompletableFuture.cancel(true) 不会中断运行中任务，超时后迟到的生成完成
 * 不得把已置 FAILED 的镜头覆盖回 READY，也不得造成 COMPLETE 计数失真/重复计数。
 */
class ImageGenerationServiceTimeoutCancelTest {

    @Test
    void timedOutBatchIsNotOverwrittenByLateCompletion() throws Exception {
        ImageGeneratorFactory factory = mock(ImageGeneratorFactory.class);
        StoryScriptRepository scriptRepository = mock(StoryScriptRepository.class);
        StoryShotRepository shotRepository = mock(StoryShotRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ImageQualityService qualityService = mock(ImageQualityService.class);
        ImageGenerator generator = mock(ImageGenerator.class);

        when(factory.getActiveGenerator()).thenReturn(generator);
        when(qualityService.isEnabled()).thenReturn(false);
        when(scriptRepository.save(any(StoryScript.class))).thenAnswer(inv -> inv.getArgument(0));

        // 记录每次 save 时的镜头状态快照，用于检测迟到覆盖
        List<String> savedStatuses = Collections.synchronizedList(new ArrayList<>());
        when(shotRepository.save(any(StoryShot.class))).thenAnswer(inv -> {
            StoryShot s = inv.getArgument(0);
            savedStatuses.add(s.getImageStatus());
            return s;
        });

        // 生成器挂起，模拟超时后仍在途的任务
        CountDownLatch block = new CountDownLatch(1);
        when(generator.generate(anyString(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            block.await(10, TimeUnit.SECONDS);
            return new GeneratedImage("http://img/1.png", "f1", "mock", 64, 64);
        });

        ImageGenerationService service = new ImageGenerationService(factory, scriptRepository,
                shotRepository, userRepository, new ObjectMapper(), qualityService,
                new com.github.ylyan2015.springaidemo.video.ShotPromptAssembler(),
                new com.github.ylyan2015.springaidemo.video.generator.ReferenceImageResolver(
                        mock(com.github.ylyan2015.springaidemo.repository.StoryCharacterRepository.class),
                        mock(com.github.ylyan2015.springaidemo.storage.StorageService.class)),
                new com.github.ylyan2015.springaidemo.video.generator.LoraResolver(false));
        setField(service, "parallelism", 1);
        setField(service, "retryAttempts", 0);
        setField(service, "retryDelayMs", 0L);
        setField(service, "defaultSize", "64x64");
        setField(service, "totalTimeoutSeconds", 1);
        service.init();

        StoryScript script = new StoryScript();
        StoryShot shot = new StoryShot();
        shot.setId(10L);
        shot.setShotOrder(1);
        shot.setImagePrompt("a cat");

        List<ImageGenerationProgress> events = Collections.synchronizedList(new ArrayList<>());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();

        try {
            Thread runner = new Thread(() -> {
                try {
                    Method m = ImageGenerationService.class.getDeclaredMethod("runBatchGeneration",
                            Long.class, StoryScript.class, List.class, VisualStyle.class,
                            SecurityContext.class, Consumer.class);
                    m.setAccessible(true);
                    m.invoke(service, 1L, script, List.of(shot), VisualStyle.fromName(null), ctx,
                            (Consumer<ImageGenerationProgress>) events::add);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            runner.start();

            // 等待总超时（1s）处理把镜头置为 FAILED
            long deadline = System.currentTimeMillis() + 8000;
            while (!savedStatuses.contains("FAILED") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(savedStatuses.contains("FAILED"), "超时应把镜头置为 FAILED");

            // 释放在途任务：修复后应检查取消标志，不再覆盖 FAILED / 不再计数
            block.countDown();
            runner.join(10_000);
            assertFalse(runner.isAlive(), "批量协调线程应已结束");

            assertFalse(savedStatuses.contains("READY"),
                    "超时后迟到完成的任务不得把 FAILED 覆盖回 READY: " + savedStatuses);

            ImageGenerationProgress complete = events.stream()
                    .filter(e -> "COMPLETE".equals(e.getType()))
                    .findFirst().orElseThrow(() -> new AssertionError("缺少 COMPLETE 事件"));
            assertEquals(0, complete.getCompletedShots(), "超时后完成计数应为 0");
            assertEquals(1, complete.getFailedShots(), "超时后失败计数应为 1（不得重复计数）");
        } finally {
            block.countDown();
            service.shutdown();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ImageGenerationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}