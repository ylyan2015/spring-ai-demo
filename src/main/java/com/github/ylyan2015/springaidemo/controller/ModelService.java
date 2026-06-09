package com.github.ylyan2015.springaidemo.controller;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型管理服务
 * 支持运行时动态切换AI模型
 */
@Service
public class ModelService {

    private final ApplicationContext applicationContext;
    private final Environment environment;
    
    // 缓存不同模型的 ChatClient，避免重复创建
    private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();
    private volatile String currentModelKey;
    
    @Value("${spring.profiles.active:deepseek,h2}")
    private String activeProfile;

    // 可用的模型配置
    private final Map<String, ModelConfig> availableModels = new LinkedHashMap<>();

    public ModelService(ApplicationContext applicationContext, Environment environment) {
        this.applicationContext = applicationContext;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        // 初始化可用模型列表
        availableModels.put("ollama", new ModelConfig(
            "ollama",
            "离线模式",
            "本地部署，免费使用，隐私安全",
            "offline"
        ));
        
        availableModels.put("deepseek", new ModelConfig(
            "deepseek",
            "DeepSeek V3",
            "在线专家模式，更强的推理能力",
            "online"
        ));
        
        availableModels.put("openai", new ModelConfig(
            "openai",
            "OpenAI GPT",
            "OpenAI 官方模型，稳定可靠",
            "online"
        ));

        // 设置当前模型（从 activeProfile 中提取第一个 profile）
        String[] profiles = activeProfile.split(",");
        String modelProfile = profiles[0].trim();
        System.out.println("初始化的 Profile: " + activeProfile);
        System.out.println("提取的模型 Profile: " + modelProfile);
        switchModel(modelProfile);
    }

    /**
     * 切换模型
     */
    public void switchModel(String modelKey) {
        if (!availableModels.containsKey(modelKey)) {
            throw new IllegalArgumentException("不支持的模型: " + modelKey);
        }

        this.currentModelKey = modelKey;
        
        System.out.println("========================================");
        System.out.println("模型已切换到: " + availableModels.get(modelKey).getName());
        System.out.println("模型 Key: " + modelKey);
        System.out.println("========================================");
    }

    /**
     * 获取当前模型
     */
    public String getCurrentModel() {
        return currentModelKey != null ? currentModelKey : "ollama";
    }

    /**
     * 获取当前模型名称
     */
    public String getCurrentModelName() {
        ModelConfig config = availableModels.get(getCurrentModel());
        return config != null ? config.getName() : "未知模型";
    }

    /**
     * 获取所有可用模型
     */
    public List<ModelConfig> getAvailableModels() {
        return new ArrayList<>(availableModels.values());
    }

    /**
     * 获取当前ChatClient（支持运行时动态切换）
     */
    public ChatClient getChatClient() {
        String currentModel = getCurrentModel();
        
        // 如果缓存中已有，直接返回
        if (chatClientCache.containsKey(currentModel)) {
            return chatClientCache.get(currentModel);
        }
        
        // 创建新的 ChatClient
        ChatClient client = createChatClient(currentModel);
        chatClientCache.put(currentModel, client);
        
        return client;
    }
    
    /**
     * 创建指定模型的 ChatClient
     */
    private ChatClient createChatClient(String modelKey) {
        // 根据当前模型类型获取对应的 ChatModel
        if ("ollama".equals(modelKey)) {
            try {
                OllamaChatModel ollamaChatModel = applicationContext.getBean(OllamaChatModel.class);
                ChatClient client = ChatClient.builder(ollamaChatModel).build();
                System.out.println("✓ 成功创建 Ollama ChatClient");
                return client;
            } catch (Exception e) {
                System.err.println("✗ 获取Ollama ChatModel失败: " + e.getMessage());
                throw new RuntimeException("获取Ollama ChatModel失败，请确保 Ollama 服务已启动: " + e.getMessage(), e);
            }
        } else if ("deepseek".equals(modelKey)) {
            return createOpenAICompatibleClient("DeepSeek", 
                environment.getProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com"),
                environment.getProperty("spring.ai.deepseek.api-key", "未设置"),
                environment.getProperty("spring.ai.deepseek.chat.options.model", "deepseek-chat"));
        } else if ("openai".equals(modelKey)) {
            return createOpenAICompatibleClient("OpenAI",
                environment.getProperty("spring.ai.openai.base-url", "https://api.openai.com"),
                environment.getProperty("spring.ai.openai.api-key", "未设置"),
                environment.getProperty("spring.ai.openai.chat.options.model", "gpt-4o-mini"));
        } else {
            throw new IllegalArgumentException("不支持的模型类型: " + modelKey);
        }
    }
    
    /**
     * 创建 OpenAI 兼容 API 的 ChatClient
     */
    private ChatClient createOpenAICompatibleClient(String provider, String baseUrl, String apiKey, String model) {
        try {
            // 使用 Spring AI 的 OpenAiApi Builder 构建
            org.springframework.ai.openai.api.OpenAiApi openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
            
            OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(
                    org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .build()
                )
                .build();
            
            ChatClient client = ChatClient.builder(openAiChatModel).build();
            
            System.out.println("========================================");
            System.out.println("✓ 成功创建 " + provider + " ChatClient");
            System.out.println("配置信息:");
            System.out.println("  Base URL: " + baseUrl);
            System.out.println("  API Key: " + (apiKey != null && apiKey.length() > 8 ? apiKey.substring(0, 8) + "..." : apiKey));
            System.out.println("  Model: " + model);
            System.out.println("========================================");
            
            return client;
        } catch (Exception e) {
            System.err.println("✗ 获取" + provider + " ChatModel失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("获取" + provider + " ChatModel失败，请检查 API Key 和网络连接: " + e.getMessage(), e);
        }
    }

    /**
     * 模型配置类
     */
    public static class ModelConfig {
        private String key;
        private String name;
        private String description;
        private String type; // offline 或 online

        public ModelConfig(String key, String name, String description, String type) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.type = type;
        }

        // Getters
        public String getKey() {
            return key;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getType() {
            return type;
        }
    }
}
