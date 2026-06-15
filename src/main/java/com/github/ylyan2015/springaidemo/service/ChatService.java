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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

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
    private final RagService ragService;

    /**
     * 最大上下文消息数量（保留最近N轮对话）
     * 每轮包含用户消息和AI回复，所以实际消息数 = maxContextMessages * 2
     */
    @Value("${chat.max-context-messages:10}")
    private int maxContextMessages;

    public ChatService(ModelService modelService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      UserRepository userRepository,
                      RagService ragService) {
        this.modelService = modelService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.ragService = ragService;
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
     * 流式发送消息并获取回复（SSE Streaming）
     * 先保存用户消息和构建上下文，然后以流式方式返回AI回复。
     * 流完成后由调用方负责保存完整的AI回复。
     *
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @return 流式AI回复片段
     */
    @Transactional
    public StreamResponse streamMessage(String sessionId, String userMessage, boolean ragEnabled) {
        // 1. 如果会话ID为空，创建新会话
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = createConversation();
        }

        // 2. 验证/创建会话
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

        // 4.5 RAG：如果启用且知识库有文档，注入相关上下文
        if (ragEnabled && ragService.hasDocuments()) {
            List<String> context = ragService.searchRelevantContext(userMessage, 3);
            if (!context.isEmpty()) {
                String ragContext = buildRagSystemPrompt(context);
                chatMessages.add(0, new SystemMessage(ragContext));
            }
        }

        // 5. 调用AI模型获取流式回复
        ChatClient chatClient = modelService.getChatClient();
        Long userId = getCurrentUserId();
        ChatOptions options = modelService.buildChatOptions(userId);

        Flux<String> contentFlux;
        if (options != null) {
            contentFlux = chatClient.prompt()
                    .options(options)
                    .messages(chatMessages)
                    .stream()
                    .content();
        } else {
            contentFlux = chatClient.prompt()
                    .messages(chatMessages)
                    .stream()
                    .content();
        }

        // 6. 是否第一条消息（用于自动生成标题）
        boolean isFirstMessage = (messageOrder == 0);

        // 7. 计算上下文使用率
        int contextUsagePercent = getContextUsagePercent(finalSessionId);

        return new StreamResponse(finalSessionId, contentFlux, isFirstMessage, userMessage, messageOrder, contextUsagePercent);
    }

    /**
     * 流完成后保存AI回复和更新会话标题
     */
    @Transactional
    public void saveStreamResult(String sessionId, String fullResponse, boolean isFirstMessage, String userMessage, int messageOrder) {
        // 保存AI回复
        Message aiMsgEntity = new Message(sessionId, "assistant", fullResponse, messageOrder + 1);
        messageRepository.save(aiMsgEntity);

        // 如果是第一条消息，自动生成标题
        if (isFirstMessage && fullResponse != null) {
            String title = generateTitle(userMessage);
            conversationRepository.findBySessionId(sessionId).ifPresent(conv -> {
                conv.setTitle(title);
                conversationRepository.save(conv);
            });
        }
    }

    /**
     * 流式响应封装
     */
    public static class StreamResponse {
        private final String sessionId;
        private final Flux<String> contentFlux;
        private final boolean isFirstMessage;
        private final String userMessage;
        private final int messageOrder;
        private final int contextUsagePercent;

        public StreamResponse(String sessionId, Flux<String> contentFlux, boolean isFirstMessage, String userMessage, int messageOrder, int contextUsagePercent) {
            this.sessionId = sessionId;
            this.contentFlux = contentFlux;
            this.isFirstMessage = isFirstMessage;
            this.userMessage = userMessage;
            this.messageOrder = messageOrder;
            this.contextUsagePercent = contextUsagePercent;
        }

        public String getSessionId() { return sessionId; }
        public Flux<String> getContentFlux() { return contentFlux; }
        public boolean isFirstMessage() { return isFirstMessage; }
        public String getUserMessage() { return userMessage; }
        public int getMessageOrder() { return messageOrder; }
        public int getContextUsagePercent() { return contextUsagePercent; }
    }

    /**
     * 发送消息并获取回复（支持多轮对话）
     *
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @return AI回复
     */
    @Transactional
    public String sendMessage(String sessionId, String userMessage, boolean ragEnabled) {
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

        // 4.5 RAG：如果启用且知识库有文档，注入相关上下文
        if (ragEnabled && ragService.hasDocuments()) {
            List<String> context = ragService.searchRelevantContext(userMessage, 3);
            if (!context.isEmpty()) {
                String ragContext = buildRagSystemPrompt(context);
                chatMessages.add(0, new SystemMessage(ragContext));
            }
        }

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
     * 获取会话上下文使用率（百分比）
     */
    public int getContextUsagePercent(String sessionId) {
        int totalMessages = (int) messageRepository.countBySessionId(sessionId);
        int maxMessages = maxContextMessages * 2;
        return Math.min(100, (int) ((double) totalMessages / maxMessages * 100));
    }

    /**
     * 压缩上下文：用AI将较早的对话总结为一条系统消息，然后删除原始旧消息
     * 压缩后上下文使用率大幅降低，同时保留关键信息
     */
    @Transactional
    public String compressContext(String sessionId) {
        // 1. 验证会话归属
        Long userId = getCurrentUserId();
        Conversation conv = conversationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作该会话");
        }

        // 2. 获取所有消息
        List<Message> allMessages = messageRepository.findBySessionIdOrderByMessageOrderAsc(sessionId);
        int maxMessages = maxContextMessages * 2;
        if (allMessages.size() <= 4) {
            return "消息数量较少，无需压缩";
        }

        // 3. 将较早的消息（保留最近 maxMessages/2 条）作为压缩目标
        int keepRecent = Math.max(maxMessages / 2, 4);
        int splitIndex = Math.max(0, allMessages.size() - keepRecent);
        List<Message> oldMessages = allMessages.subList(0, splitIndex);
        List<Message> recentMessages = allMessages.subList(splitIndex, allMessages.size());

        if (oldMessages.isEmpty()) {
            return "没有需要压缩的旧消息";
        }

        // 4. 构建旧对话文本，交给AI做总结
        StringBuilder oldText = new StringBuilder();
        for (Message msg : oldMessages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            oldText.append(role).append(": ").append(msg.getContent()).append("\n");
        }

        String summary;
        try {
            ChatClient chatClient = modelService.getChatClient();
            summary = chatClient.prompt()
                    .user("请将以下对话内容用简洁的中文总结，保留关键信息和结论，不超过300字：\n\n" + oldText)
                    .call()
                    .content();
        } catch (Exception e) {
            throw new RuntimeException("AI总结失败: " + e.getMessage(), e);
        }

        // 5. 删除旧消息
        for (Message msg : oldMessages) {
            messageRepository.delete(msg);
        }

        // 6. 插入一条 system 摘要消息（order=0），并将剩余消息的 order 重新编号
        Message summaryMsg = new Message(sessionId, "system",
                "[对话历史摘要] " + summary, 0);
        messageRepository.save(summaryMsg);

        int order = 1;
        for (Message msg : recentMessages) {
            msg.setMessageOrder(order++);
            messageRepository.save(msg);
        }

        System.out.println("✓ 上下文已压缩: 删除 " + oldMessages.size() + " 条旧消息，生成摘要");
        return "上下文已压缩，" + oldMessages.size() + " 条旧消息已总结为摘要";
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
     * 构建 RAG 上下文系统提示
     */
    private String buildRagSystemPrompt(List<String> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following reference documents. Use them to answer the user's question if relevant. ");
        sb.append("If the documents are not relevant, answer based on your own knowledge.\n\n");
        sb.append("--- Reference Documents ---\n");
        for (int i = 0; i < context.size(); i++) {
            sb.append("[Doc ").append(i + 1).append("] ").append(context.get(i)).append("\n\n");
        }
        sb.append("--- End of Reference ---");
        return sb.toString();
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
