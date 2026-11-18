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

import java.util.ArrayList;
import java.util.List;

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
        return generateScript(creativeIdea, templateGuidance, null);
    }

    /**
     * Generate a structured storyboard with template guidance and the user's asset catalog (Task 13, spec §5.2)
     *
     * @param assetCatalog 素材清单文本（每行「[人物]/[场景] 名：标签」），null/空白时零注入
     */
    public Storyboard generateScript(String creativeIdea, String templateGuidance, String assetCatalog) {
        BeanOutputConverter<Storyboard> converter = new BeanOutputConverter<>(Storyboard.class);

        String systemPrompt = """
                你是一位专业的短视频导演和编剧，擅长将创意概念转化为引人入胜、画面感强烈的分镜脚本。

                请根据用户的创意想法，生成一个包含4-6个镜头的短视频分镜脚本。

                要求：
                0. 先人设后分镜：characters 数组列出全部出场角色（name 中文角色名；featureTable 英文固定特征表：发型/瞳色/服装/体型，跨镜复用锁定身份；personality 性格关键词；suggestedVoice 可选 edge-tts 声线名）
                1. 标题简洁有力，能概括视频主题
                2. 主题描述一句话概括视频核心思想
                3. 每个镜头包含：
                   - order: 镜头顺序（从1开始）
                   - sceneDescription: 画面内容描述（中文，四要素缺一不可：①主体——谁/什么+外观服装状态 ②动作 ③具体背景环境 ④光影色调；情绪通过表情/肢体表达，不写抽象形容词）
                   - cameraMovement: 镜头运动方式（如：固定、推进、拉远、平移、俯拍、仰拍等）
                   - durationSeconds: 镜头时长（3-8秒）
                   - narration: 旁白文字（中文，字数 ≤ round(2.5 × durationSeconds)，配合画面的解说或台词）
                   - imagePrompt: 用于AI图像生成的提示词（必须用英文，固化公式：subject + action + environment details + lighting + color palette + composition + art style；环境细节至少 2 个具体物件）
                   - characterNames: 本镜出场角色名数组（必须与 characters 中的 name 一致）
                   - shotSize: 景别（CLOSE/MEDIUM/LONG）
                   - dialogue: 本镜角色对白（中文，无对白为空串；与旁白分离，不要重复旁白内容）
                   - dialogueCharacterName: 对白角色名（必须与 characters 中的 name 一致，无对白为空串）

                画面感禁令：
                - 禁止空泛描述（beautiful scene / nice view / 温馨的画面 等），一律落到具体物件、光线、色彩
                - 除尾卡外禁止无背景纯色镜头；同一场戏角色服装状态保持一致

                钩子叙事硬约束（短视频留存基本盘）：
                - 首镜 3 秒内必须抛出冲突/悬念：用危机、反常识事实或尖锐提问开场，禁止风景铺垫或慢热引入；首镜旁白控制在 15 字以内把钩子说出口
                - 每 15-20 秒安排一个情绪转折：冲突升级、信息反转或情绪落差，禁止全程平铺直叙
                - 末镜留扣子：悬念未解或下集预告，接尾卡关注 CTA（有尾卡镜时扣子放在倒数第二镜）
                - 台词密度：镜头时长 ≥ (旁白+对白字数)/5.5 + 0.6 秒，写不下就删字或加时长，不要硬塞

                范例镜（高质量标杆）：
                sceneDescription: 林晚（黑长直、红色大衣被雨水打湿）蹲在巷口逗一只橘猫，身后是霓虹招牌林立的窄巷，青石板路面积水倒映着暖黄与品红的灯光，雨丝在逆光中发亮。
                imagePrompt: 1girl, black long hair, wet red coat, crouching, feeding an orange cat, narrow alley with neon signs, wet cobblestone reflections, warm yellow and magenta lights, backlit raindrops, dusk, cinematic composition, anime coloring, best quality

                注意：imagePrompt 是喂给图像生成模型的，必须是高质量的英文视觉描述，不要包含文字/对白内容。
                每个镜头的 imagePrompt 应保持视觉风格一致。
                """;

        // 素材清单注入（spec §5.2）：基础 prompt + 清单 + templateGuidance（模块 H 共存）
        // 顺序不可颠倒：guidance 在最后，模板风格才能盖在清单之后（清单是可选内容，风格是本次的硬约束）
        if (assetCatalog != null && !assetCatalog.isBlank()) {
            systemPrompt = systemPrompt + """

                    可用素材库（用户已备参考图，优先使用）：
                    """ + assetCatalog + """
                    指令：优先用库内人物当主角（角色名与库一致）、库内场景当背景；库不够时可原创补充。
                    """;
        }
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

        // P0-2 台词密度自动校验：只 warn 不阻断（LLM 输出不可强控，compose 的 TTS 预探测抬升是兜底）
        List<String> densityIssues = densityViolations(storyboard.getShots());
        if (!densityIssues.isEmpty()) {
            log.warn("台词密度校验发现 {} 处超密（合成时 TTS 预探测会抬升时长兜底）: {}",
                    densityIssues.size(), densityIssues);
        }

        return storyboard;
    }

    /** 旁白实测语速基准（YunyangNeural +15% ≈ 5.5 字/秒，2026-11-17 实测）与镜头呼吸余量（秒） */
    static final double CHARS_PER_SECOND = 5.5;
    static final double DENSITY_HEADROOM_SECONDS = 0.6;

    /**
     * P0-2 台词密度校验：镜头时长 ≥ (旁白+对白字数)/5.5 + 0.6s。
     * 对白与旁白同镜先后合成都占 TTS 时长，须合计；尾卡本地压字不走 TTS 跳过。
     * 返回违规描述列表（镜序+字数+需求时长），供调用方 warn。
     */
    static List<String> densityViolations(List<Shot> shots) {
        List<String> violations = new ArrayList<>();
        if (shots == null) {
            return violations;
        }
        for (Shot shot : shots) {
            if (Boolean.TRUE.equals(shot.getEndCard())) {
                continue;
            }
            int chars = spokenLength(shot.getNarration()) + spokenLength(shot.getDialogue());
            double required = chars / CHARS_PER_SECOND + DENSITY_HEADROOM_SECONDS;
            if (required > shot.getDurationSeconds()) {
                violations.add(String.format("镜%d 台词超密：%d 字需≥%.1fs，实际 %ds",
                        shot.getOrder(), chars, required, shot.getDurationSeconds()));
            }
        }
        return violations;
    }

    private static int spokenLength(String text) {
        return text == null || text.isBlank() ? 0 : text.length();
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
