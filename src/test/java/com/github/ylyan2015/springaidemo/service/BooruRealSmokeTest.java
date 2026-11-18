package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider;
import com.github.ylyan2015.springaidemo.video.generator.BooruProvider.BooruPost;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S4 booru 真实网络冒烟（默认跳过；-Dbooru=true 执行，需外网可达）。
 * 存在理由：BooruSearchServiceTest 的 26 例全是打桩的 http/provider，一次真实响应都没验过——
 * 站点改版、字段改名、白名单漂移这三类故障只有这里能抓到。
 * 副作用（已知代价）：会往当前 profile 的库与 uploads 目录写冒烟用户与素材，用户名带时间戳后缀避免撞唯一约束。
 */
@SpringBootTest
class BooruRealSmokeTest {

    @Autowired BooruSearchService service;
    @Autowired UserRepository userRepository;
    @Autowired List<BooruProvider> providers;

    /** 每次跑用一个新账号：素材归属校验（getCurrentUserId）要求上下文里有真实 User 行 */
    private void loginAsSmokeUser() {
        User u = new User();
        u.setUsername("booru_smoke_" + System.currentTimeMillis() % 100000);
        u.setPassword("smoke-not-for-login");
        userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getUsername(), null, List.of()));
    }

    @Test
    void searchAndIngestRealPost() {
        assumeTrue(Boolean.getBoolean("booru"), "真实冒烟需 -Dbooru=true");
        loginAsSmokeUser();
        try {
            var posts = service.search("hatsune_miku", "safebooru", 0);
            assumeTrue(!posts.isEmpty(), "safebooru 无结果（网络/站点变化），跳过");
            assertTrue(posts.size() <= 20);
            // 计划这句写的是 {safe, g}：真实 dapi 返长式 general，照抄后本例在真站点上必红（实测）。
            // 判据与 BooruSearchService.SAFE_RATINGS 同一份（它的内容由单测钉住），这里只能手工同步
            java.util.Set<String> safeVocab = java.util.Set.of("safe", "g", "general");
            posts.forEach(p -> {
                assertTrue(safeVocab.contains(p.rating()), "非安全分级进了结果列表: " + p);
                assertTrue(p.fileUrl().startsWith("https://safebooru.org/"), p.fileUrl());
            });
            MediaAsset a = service.ingest("safebooru", posts.get(0).postId(), "CHARACTER", "初音冒烟", null);
            assertNotNull(a.getFileId());
            assertEquals("IMAGE", a.getKind());
            assertTrue(a.getSourceNote().contains("safebooru.org/index.php?page=post"));
            // 红线复证（真实数据版）：入库后 url 必须是自家存储路径，绝不可能是 safebooru 直链
            assertFalse(a.getUrl().contains("safebooru"), a.getUrl());
            assertTrue(a.getUrl().startsWith("/images/"), a.getUrl());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 备源专项（Task 12 前置核验的真实证据位）：donmai 系图片托管在 cdn.donmai.us，
     * 早筛或 allowed-hosts 任一侧没同步放宽，这里都会看到「搜索成功但 0 结果」而不是报错。
     */
    @Test
    void danbooruSafeSourceReturnsWhitelistAcceptablePosts() {
        assumeTrue(Boolean.getBoolean("booru"), "真实冒烟需 -Dbooru=true");
        loginAsSmokeUser();
        // 清理必须包在最外层：assumeTrue 抛的 TestAbortedException 是 RuntimeException，
        // 直接弹出方法的话内层 finally 根本不会执行，烟测账号的鉴权上下文会泄到同线程的下一个测试（审查 W-2）
        try {
            List<BooruPost> posts;
            try {
                posts = service.search("hatsune_miku", "danbooru-safe", 0);
            } catch (RuntimeException e) {
                // 源站侧 403/5xx 属环境不可达（本机 IP 被 donmai 拒），不是本计划的缺陷：跳过而非失败
                assumeTrue(false, "danbooru-safe 不可达: " + e.getMessage());
                return;
            }
            assertFalse(posts.isEmpty(), "danbooru-safe 整源落空：检查适配器早筛与 allowed-hosts 是否同步");
            posts.forEach(p -> {
                assertTrue(java.util.Set.of("safe", "g", "general").contains(p.rating()), p.toString());
                assertTrue(p.fileUrl().startsWith("https://safebooru.donmai.us/")
                                || p.fileUrl().startsWith("https://cdn.donmai.us/"),
                        "file_url 域外，白名单与早筛疑似漂移: " + p.fileUrl());
                assertTrue(p.previewUrl().startsWith("https://"), p.previewUrl());
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 接线核验（不受 -Dbooru 开关影响、零外网）：面板下拉的 value 必须逐个等于 @Component 的 name()。
     * 任一侧改名都会让另一侧 400「未知 booru 源」，而服务层单测里 provider 是 mock，看不见这条跨层契约。
     */
    @Test
    void panelProviderOptionsMatchTheWiredBeanNames() throws Exception {
        java.util.Set<String> beanNames = providers.stream()
                .map(BooruProvider::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(beanNames.containsAll(java.util.Set.of("safebooru", "danbooru-safe")), beanNames.toString());
        String html;
        try (var in = new ClassPathResource("templates/video-studio.html").getInputStream()) {
            html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int from = html.indexOf("id=\"booruProvider\"");
        assertTrue(from >= 0, "面板里的 booruProvider 下拉消失（Task 12 交付物）");
        String options = html.substring(from, html.indexOf("</select>", from));
        var m = java.util.regex.Pattern.compile("value=\"([^\"]*)\"").matcher(options);
        int counted = 0;
        while (m.find()) {
            String v = m.group(1);
            assertTrue(beanNames.contains(v), "面板列了未装配的源（选了必 400）: " + v);
            counted++;
        }
        assertEquals(beanNames.size(), counted, "面板下拉项数与装配的源数不一致: " + options);
    }
}
