package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import com.github.ylyan2015.springaidemo.video.Storyboard;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评论脚本 format 分离 + JSON 导入测试（Phase 5 Track B Task 2）
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:commentaryfmt"})
@org.springframework.transaction.annotation.Transactional
class StoryScriptCommentaryFormatTest {

    @Autowired StoryScriptService service;
    @Autowired UserRepository userRepository;

    @BeforeEach void login(org.junit.jupiter.api.TestInfo info) {
        // 每用例独立用户名：共享 mem 库下避免重复插入唯一约束冲突，且 listScripts 按 userId 天然隔离
        String username = "fmt-" + info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("x");
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test void importCreatesCommentaryScriptWithAttribution() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", "钱坑漫评01");
        json.put("shots", List.of(
                Map.of("durationSeconds", 8, "narration", "n1"),
                Map.of("durationSeconds", 6, "narration", "n2",
                        "insert", Map.of("id", "A", "seconds", 3, "attribution", "来源：第1话 · 评论引用"))));
        StoryScript s = service.importCommentary(json);
        assertEquals("commentary", s.getFormat());
        assertEquals(2, s.getShots().size());
        assertEquals("来源：第1话 · 评论引用", s.getShots().get(1).getInsertAttribution());
        assertNull(s.getShots().get(0).getInsertAttribution());
    }

    @Test void listSeparatesCommentaryFromDirector() {
        service.importCommentary(Map.of("title", "c", "shots", List.of(Map.of("durationSeconds", 5))));
        Storyboard sb = new Storyboard();
        sb.setTitle("d");
        sb.setShots(List.of());
        service.saveFromStoryboard(null, sb, "");
        assertEquals(1, service.listScripts("commentary").size());
        assertEquals(1, service.listScripts().size());
        assertEquals("d", service.listScripts().get(0).getTitle());
    }

    @Test void importRejectsEmptyShots() {
        assertThrows(IllegalArgumentException.class, () -> service.importCommentary(Map.of("title", "x")));
    }

    @Test void updateShotPersistsAttribution() {
        StoryScript s = service.importCommentary(Map.of("title", "c", "shots", List.of(Map.of("durationSeconds", 5))));
        Long shotId = s.getShots().get(0).getId();
        service.updateShot(s.getId(), shotId, Map.of("insertAttribution", "来源：第4话"));
        assertEquals("来源：第4话", service.getScript(s.getId()).getShots().get(0).getInsertAttribution());
    }

    @Test void updateFormatWhitelist() {
        StoryScript s = service.importCommentary(Map.of("title", "c", "shots", List.of(Map.of("durationSeconds", 5))));
        // 大小写/非法值拒绝（防静默错分）；合法值与清空放行
        assertThrows(IllegalArgumentException.class, () -> service.updateFormat(s.getId(), "Commentary"));
        assertThrows(IllegalArgumentException.class, () -> service.updateFormat(s.getId(), "bogus"));
        assertEquals("commentary", service.updateFormat(s.getId(), "commentary").getFormat());
        assertNull(service.updateFormat(s.getId(), null).getFormat());
    }
}