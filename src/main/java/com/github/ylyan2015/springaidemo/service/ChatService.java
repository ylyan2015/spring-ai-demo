package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.entity.Conversation;
import com.github.ylyan2015.springaidemo.entity.Message;
import com.github.ylyan2015.springaidemo.entity.User;
import com.github.ylyan2015.springaidemo.repository.ConversationRepository;
import com.github.ylyan2015.springaidemo.repository.MessageRepository;
import com.github.ylyan2015.springaidemo.repository.UserRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天服务类
 * 处理多轮对话、上下文记忆等核心业务逻辑
 * 支持多种AI模型：Ollama、OpenAI、DeepSeek等
 */
@Service
public class ChatService {

    private final ModelService modelService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    /**
     * 最大上下文消息数量（保留最近N轮对话）
     * 每轮包含用户消息和AI回复，所以实际消息数 = maxContextMessages * 2
     */
    @Value("${chat.max-context-messages:10}")
    private int maxContextMessages;

    public ChatService(ModelService modelService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      UserRepository userRepository) {
        this.modelService = modelService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    /**
     * 创建新会话（绑定到当前用户）
     *
     * @return 会话ID
     */
    @Transactional
    public String createConversation() {
        Long userId = getCurrentUserId();
        String sessionId = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(sessionId, userId);
        conversationRepository.save(conversation);
        return sessionId;
    }

    /**
     * 获取当前登录用户的所有会话
     */
    public List<Conversation> getUserConversations() {
        Long userId = getCurrentUserId();
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new RuntimeException("用户未登录");
        }
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return user.getId();
    }

    /**
     * 发送消息并获取回复（支持多轮对话）
     *
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @return AI回复
     */
    @Transactional
    public String sendMessage(String sessionId, String userMessage) {
        // 1. 如果会话ID为空，创建新会话
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = createConversation();
        }

        // 2. 验证会话是否存在（使用final变量以在lambda中使用）
        final String finalSessionId = sessionId;
        Conversation conversation = conversationRepository.findBySessionId(finalSessionId)
                .orElseGet(() -> {
                    Long uid = getCurrentUserId();
                    Conversation newConv = new Conversation(finalSessionId, uid);
                    return conversationRepository.save(newConv);
                });

        // 3. 保存用户消息
        int messageOrder = (int) messageRepository.countBySessionId(finalSessionId);
        Message userMsgEntity = new Message(finalSessionId, "user", userMessage, messageOrder);
        messageRepository.save(userMsgEntity);

        // 4. 构建带上下文的对话历史
        List<Message> recentMessages = getRecentMessages(finalSessionId);
        List<org.springframework.ai.chat.messages.Message> chatMessages = buildChatHistory(recentMessages);

        // 5. 调用AI模型获取回复（动态应用参数预设）
        String aiResponse;
        try {
            ChatClient chatClient = modelService.getChatClient();
            Long userId = getCurrentUserId();
            ChatOptions options = modelService.buildChatOptions(userId);
            if (options != null) {
                aiResponse = chatClient.prompt()
                        .options(options)
                        .messages(chatMessages)
                        .call()
                        .content();
            } else {
                aiResponse = chatClient.prompt()
                        .messages(chatMessages)
                        .call()
                        .content();
            }
        } catch (Exception e) {
            String modelName = modelService.getCurrentModelName();
            String errorMsg = e.getMessage();
            
            // 提供更友好的错误提示
            if (errorMsg != null && (errorMsg.contains("ClosedChannel") || errorMsg.contains("ConnectException"))) {
                errorMsg = "网络连接失败，请检查：\n" +
                          "1. 如果使用离线模式，请确保 Ollama 服务已启动（ollama serve）\n" +
                          "2. 如果使用在线模式，请检查网络连接和 API Key 是否正确\n" +
                          "3. 防火墙或代理可能阻止了连接";
            } else if (errorMsg != null && errorMsg.contains("401")) {
                errorMsg = "API Key 无效或已过期，请检查配置";
            } else if (errorMsg != null && errorMsg.contains("404")) {
                errorMsg = "模型不存在，请检查模型名称配置";
            }
            
            throw new RuntimeException("调用AI模型失败 [" + modelName + "]: \n" + errorMsg, e);
        }

        // 6. 保存AI回复
        Message aiMsgEntity = new Message(finalSessionId, "assistant", aiResponse, messageOrder + 1);
        messageRepository.save(aiMsgEntity);

        // 7. 如果是对话的第一条消息，自动生成标题
        if (messageOrder == 0 && aiResponse != null) {
            String title = generateTitle(userMessage);
            conversation.setTitle(title);
            conversationRepository.save(conversation);
        }

        return aiResponse;
    }

    /**
     * 获取会话历史消息
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    public List<Message> getConversationHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId);
    }

    /**
     * 删除会话（仅允许拥有者删除）
     *
     * @param sessionId 会话ID
     */
    @Transactional
    public void deleteConversation(String sessionId) {
        Long userId = getCurrentUserId();
        Conversation conv = conversationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除该会话");
        }
        messageRepository.deleteBySessionId(sessionId);
        conversationRepository.deleteBySessionId(sessionId);
    }

    /**
     * 获取最近的N条消息用于上下文
     *
     * @param sessionId 会话ID
     * @return 最近的消息列表
     */
    private List<Message> getRecentMessages(String sessionId) {
        List<Message> allMessages = messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId);
        
        // 如果消息数量超过限制，只保留最近的N条
        if (allMessages.size() > maxContextMessages * 2) {
            return allMessages.subList(allMessages.size() - maxContextMessages * 2, allMessages.size());
        }
        
        return allMessages;
    }

    /**
     * 将数据库消息转换为Spring AI聊天消息
     *
     * @param messages 数据库消息列表
     * @return Spring AI消息列表
     */
    private List<org.springframework.ai.chat.messages.Message> buildChatHistory(List<Message> messages) {
        return messages.stream()
                .map(msg -> {
                    if ("user".equalsIgnoreCase(msg.getRole())) {
                        return new UserMessage(msg.getContent());
                    } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                        return new AssistantMessage(msg.getContent());
                    } else if ("system".equalsIgnoreCase(msg.getRole())) {
                        return new SystemMessage(msg.getContent());
                    }
                    return null;
                })
                .filter(msg -> msg != null)
                .collect(Collectors.toList());
    }

    /**
     * 生成会话标题（简单实现：取用户消息的前20个字符）
     *
     * @param firstMessage 第一条消息
     * @return 会话标题
     */
    private String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isEmpty()) {
            return "新对话";
        }
        
        // 去除换行符，截取前20个字符
        String title = firstMessage.replaceAll("\\s+", " ");
        return title.length() > 20 ? title.substring(0, 20) + "..." : title;
    }
}
