package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.Conversation;
import com.github.ylyan2015.springaidemo.entity.Message;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ConversationRepository;
import com.github.ylyan2015.springaidemo.repository.MessageRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * compressContext 事务边界测试（release 复审 M-1）：
 * LLM 总结必须在事务外（M9），但删除旧消息→存摘要→重编号的破坏性多写序列
 * 必须在同一事务内，中途 DB 失败可整体回滚，避免聊天消息永久丢失。
 */
class ChatServiceCompressTransactionTest {

    private ModelService modelService;
    private MessageRepository messageRepository;
    private TransactionTemplate transactionTemplate;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RagService ragService = mock(RagService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        chatService = new ChatService(modelService, conversationRepository, messageRepository,
                userRepository, ragService, transactionTemplate);

        User me = new User();
        me.setId(1L);
        me.setUsername("tester");
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(me));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", "pw", List.of()));
        when(conversationRepository.findBySessionId("s1"))
                .thenReturn(Optional.of(new Conversation("s1", 1L)));

        // 8 条消息：maxContextMessages 未注入为 0 → keepRecent=4，前 4 条为压缩目标
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            messages.add(new Message("s1", i % 2 == 0 ? "user" : "assistant", "msg" + i, i));
        }
        when(messageRepository.findBySessionIdOrderByMessageOrderAsc("s1")).thenReturn(messages);

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("摘要内容");
        when(modelService.getChatClient()).thenReturn(chatClient);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void destructiveWritesHappenInsideTransaction() {
        doNothing().when(transactionTemplate).executeWithoutResult(any());

        chatService.compressContext("s1");

        // 事务回调执行前不应发生任何破坏性写入（全部包在事务内）
        verify(messageRepository, never()).delete(any(Message.class));
        verify(messageRepository, never()).save(any(Message.class));

        ArgumentCaptor<Consumer<TransactionStatus>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(transactionTemplate, times(1)).executeWithoutResult(captor.capture());

        // 执行事务回调：4 条删除 + 1 条摘要 + 4 条重编号
        captor.getValue().accept(mock(TransactionStatus.class));
        verify(messageRepository, times(4)).delete(any(Message.class));
        verify(messageRepository, times(5)).save(any(Message.class));
    }

    @Test
    void llmSummaryRunsOutsideTransaction() {
        doNothing().when(transactionTemplate).executeWithoutResult(any());

        chatService.compressContext("s1");

        // LLM 调用（getChatClient）必须先于事务开启，长调用不占事务（M9 语义保留）
        InOrder inOrder = inOrder(modelService, transactionTemplate);
        inOrder.verify(modelService).getChatClient();
        inOrder.verify(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void transactionFailurePropagates() {
        doThrow(new RuntimeException("db down")).when(transactionTemplate).executeWithoutResult(any());

        RuntimeException e = assertThrows(RuntimeException.class, () -> chatService.compressContext("s1"));
        assertEquals("db down", e.getMessage());
    }
}