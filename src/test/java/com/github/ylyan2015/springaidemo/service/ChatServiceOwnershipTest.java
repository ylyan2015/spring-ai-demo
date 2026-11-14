package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.Conversation;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ConversationRepository;
import com.github.ylyan2015.springaidemo.repository.MessageRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 聊天会话归属校验测试（release 审查 M3：IDOR 防护）
 * getConversationHistory/getLatestAssistantMessage/getConversation/streamMessage/sendMessage
 * 必须校验会话归属，禁止跨用户读写。
 */
class ChatServiceOwnershipTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ModelService modelService = mock(ModelService.class);
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        RagService ragService = mock(RagService.class);
        chatService = new ChatService(modelService, conversationRepository, messageRepository,
                userRepository, ragService, mock(org.springframework.transaction.support.TransactionTemplate.class));

        User me = new User();
        me.setId(1L);
        me.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(me));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));

        // 他人会话：userId=2
        Conversation otherConv = new Conversation("other-session", 2L);
        when(conversationRepository.findBySessionId("other-session")).thenReturn(Optional.of(otherConv));
        // 自己的会话
        Conversation myConv = new Conversation("my-session", 1L);
        when(conversationRepository.findBySessionId("my-session")).thenReturn(Optional.of(myConv));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void historyOfOtherUserIsRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> chatService.getConversationHistory("other-session"));
        assertTrue(e.getMessage().contains("无权"));
        verify(messageRepository, never()).findBySessionIdOrderByMessageOrderAsc(anyString());
    }

    @Test
    void latestAssistantOfOtherUserIsRejected() {
        assertThrows(RuntimeException.class,
                () -> chatService.getLatestAssistantMessage("other-session"));
        verify(messageRepository, never()).findBySessionIdOrderByMessageOrderAsc(anyString());
    }

    @Test
    void conversationInfoOfOtherUserIsRejected() {
        assertThrows(RuntimeException.class,
                () -> chatService.getConversation("other-session"));
    }

    @Test
    void sendMessageToOtherUserSessionIsRejected() {
        assertThrows(RuntimeException.class,
                () -> chatService.sendMessage("other-session", "hi", false));
        verify(messageRepository, never()).save(any());
    }

    @Test
    void streamMessageToOtherUserSessionIsRejected() {
        assertThrows(RuntimeException.class,
                () -> chatService.streamMessage("other-session", "hi", false));
        verify(messageRepository, never()).save(any());
    }

    @Test
    void ownSessionHistoryStillWorks() {
        when(messageRepository.findBySessionIdOrderByMessageOrderAsc("my-session")).thenReturn(List.of());
        assertNotNull(chatService.getConversationHistory("my-session"));
    }

    @Test
    void anonymousIsRejected() {
        SecurityContextHolder.clearContext();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> chatService.getConversationHistory("my-session"));
        assertTrue(e.getMessage().contains("未登录"));
    }
}