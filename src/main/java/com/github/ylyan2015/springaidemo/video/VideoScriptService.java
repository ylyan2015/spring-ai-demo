package com.github.ylyan2015.springaidemo.video;

import com.github.ylyan2015.springaidemo.controller.ModelService;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateRequest;
import com.github.ylyan2015.springaidemo.dto.ImageGenerateResponse;
import com.github.ylyan2015.springaidemo.service.ImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service
public class VideoScriptService {

    private static final Logger log = LoggerFactory.getLogger(VideoScriptService.class);

    private final ModelService modelService;
    private final ImageService imageService;

    public VideoScriptService(ModelService modelService, ImageService imageService) {
        this.modelService = modelService;
        this.imageService = imageService;
    }

    /**
     * Generate a structured storyboard from a creative idea using LLM
     */
    public Storyboard generateScript(String creativeIdea) {
        return generateScript(creativeIdea, null);
    }

    /**
     * Generate a structured storyboard with optional template guidance (模块 H)
     *
     * @param templateGuidance 模板风格引导（stylePrefix + 推荐镜头数），null/空白时不注入
     */
    public Storyboard generateScript(String creativeIdea, String templateGuidance) {
        BeanOutputConverter<Storyboard> converter = new BeanOutputConverter<>(Storyboard.class);

        String systemPrompt = """
                你是一位专业的短视频导演和编剧，擅长将创意概念转化为引人入胜的分镜脚本。

                请根据用户的创意想法，生成一个包含4-6个镜头的短视频分镜脚本。

                要求：
                0. 先人设后分镜：characters 数组列出全部出场角色（name 中文角色名；featureTable 英文固定特征表：发型/瞳色/服装/体型，跨镜复用锁定身份；personality 性格关键词；suggestedVoice 可选 edge-tts 声线名）
                1. 标题简洁有力，能概括视频主题
                2. 主题描述一句话概括视频核心思想
                3. 每个镜头包含：
                   - order: 镜头顺序（从1开始）
                   - sceneDescription: 画面内容描述（中文，详细描述场景、人物、动作）
                   - cameraMovement: 镜头运动方式（如：固定、推进、拉远、平移、俯拍、仰拍等）
                   - durationSeconds: 镜头时长（3-8秒）
                   - narration: 旁白文字（中文，配合画面的解说或台词）
                   - imagePrompt: 用于AI图像生成的提示词（必须用英文，详细描述视觉元素：场景类型、主体、环境、光影、色调、构图、艺术风格）
                   - characterNames: 本镜出场角色名数组（必须与 characters 中的 name 一致）
                   - shotSize: 景别（CLOSE/MEDIUM/LONG）
                   - dialogue: 本镜角色对白（中文，无对白为空串；与旁白分离，不要重复旁白内容）
                   - dialogueCharacterName: 对白角色名（必须与 characters 中的 name 一致，无对白为空串）

                注意：imagePrompt 是喂给图像生成模型的，必须是高质量的英文视觉描述，不要包含文字/对白内容。
                每个镜头的 imagePrompt 应保持视觉风格一致。
                """;

        // 模块 H：模板风格引导追加到 System Prompt 末尾
        if (templateGuidance != null && !templateGuidance.isBlank()) {
            systemPrompt = systemPrompt + "\n" + templateGuidance;
        }

        String userMessage = String.format("""
                请将以下创意想法转化为短视频分镜脚本：

                创意想法：%s

                %s
                """, creativeIdea, converter.getFormat());

        ChatClient chatClient = modelService.getChatClient();

        log.info("Generating storyboard for idea: {}", creativeIdea);
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();

        log.debug("LLM response: {}", response);

        Storyboard storyboard = converter.convert(response);
        log.info("Storyboard generated: title='{}', {} shots",
                storyboard.getTitle(),
                storyboard.getShots() != null ? storyboard.getShots().size() : 0);

        return storyboard;
    }

    /**
     * Generate an image for a specific shot using DashScope
     */
    public ImageGenerateResponse generateImageForShot(Shot shot) {
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt(shot.getImagePrompt());
        request.setSize("1280x720");
        request.setN(1);
        return imageService.generateImage(request);
    }
}
