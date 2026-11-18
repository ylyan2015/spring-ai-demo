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
     * 生成-评价-生成精修轮数（0=关闭）。@Value 只在 Spring 上下文生效，单测直接 new 时保持 0，
     * 存量测试零扰动；运行期默认由 application.yml 注入 2。
     */
    @org.springframework.beans.factory.annotation.Value("${video.script.refine-rounds:2}")
    private int refineRounds;

    /**
     * Generate a structured storyboard with template guidance and the user's asset catalog (Task 13, spec §5.2)
     *
     * @param assetCatalog 素材清单文本（每行「[人物]/[场景] 名：标签」），null/空白时零注入
     */
    /**
     * 四人评审团（单 prompt 串联）：只质疑不改写，质疑维度必须对照既有硬约束（钩子/画面感/密度），
     * 否则评审会发明新偏好把首轮稿带偏；条数上限防意见通胀。
     */
    private static final String CRITIC_SYSTEM = """
            你是短视频评审团，从四个专业视角审视分镜脚本，只质疑、不改写：
            - 导演：镜头语法与留存节奏——首镜 3 秒钩子是否真抛出冲突/悬念；景别与运镜是否服务叙事；转折是否落在 15-20 秒内；末镜是否留扣子
            - 编剧：因果链与台词——冲突是否升级、信息反转是否成立；对白是否推进剧情；旁白是否与画面重复啰嗦
            - 画师：imagePrompt 可画性——英文公式要素是否齐（主体/动作/≥2 环境物件/光影）；跨镜风格是否一致；有无纯色背景/抽象形容词违规；角色特征表能否锁定身份
            - 乐师：声画时长——台词密度（时长≥字数/5.5+0.6 秒）；旁白情绪曲线与 BGM 落点是否匹配
            输出规则：每行一条「[视角] 镜N：问题 → 具体改法」；最多 10 条，按对留存的影响排序；没有问题的视角不发言；不要重写脚本，不要总结客套。
            """;

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

        ChatClient chatClient = modelService.getScriptChatClient();

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
        
        // 生成-评价-生成：默认 2 轮精修，任一环节失败降级保留当前最优稿（绝不拖垮生成）
        storyboard = refineStoryboard(chatClient, systemPrompt, converter, storyboard);
        
        // P0-2 台词密度自动校验（对最终稿）：只 warn 不阻断（LLM 输出不可强控，compose 的 TTS 预探测抬升是兜底）
        List<String> densityIssues = densityViolations(storyboard.getShots());
        if (!densityIssues.isEmpty()) {
            log.warn("台词密度校验发现 {} 处超密（合成时 TTS 预探测会抬升时长兜底）: {}",
                    densityIssues.size(), densityIssues);
        }

        return storyboard;
    }

    /**
     * 生成-评价-生成精修循环：每轮「评审团质疑 → 原硬约束下重修」。降级红线：评审调用失败、
     * 意见空白、重修产物不可解析——任一发生即止损返回当前最优稿，精修永远不拖垮生成。
     */
    private Storyboard refineStoryboard(ChatClient chatClient, String systemPrompt,
                                        BeanOutputConverter<Storyboard> converter, Storyboard draft) {
        Storyboard current = draft;
        for (int round = 1; round <= refineRounds; round++) {
            String critique;
            try {
                critique = chatClient.prompt()
                        .system(CRITIC_SYSTEM)
                        .user("待评审的分镜脚本 JSON：\n" + toJson(current))
                        .call()
                        .content();
            } catch (Exception e) {
                log.warn("精修第 {} 轮评审调用失败，降级保留当前稿: {}", round, e.getMessage());
                return current;
            }
            if (critique == null || critique.isBlank()) {
                log.info("精修第 {} 轮评审返回空意见，停止精修", round);
                return current;
            }
            log.debug("精修第 {} 轮评审意见:\n{}", round, critique);
            try {
                String revised = chatClient.prompt()
                        .system(systemPrompt)
                        .user(String.format("""
                                以下是你此前生成的分镜脚本与专业评审团的修改意见。
                                请在保持全部硬约束（钩子叙事/画面感禁令/台词密度/角色一致性）的前提下按意见修改，输出完整的改进版脚本。

                                当前脚本 JSON：
                                %s

                                评审意见：
                                %s

                                %s
                                """, toJson(current), critique, converter.getFormat()))
                        .call()
                        .content();
                current = converter.convert(revised);
                log.info("Refine round {} done: title='{}'", round, current.getTitle());
                log.debug("精修第 {} 轮产出:\n{}", round, toJson(current));
            } catch (Exception e) {
                log.warn("精修第 {} 轮重修失败（含格式不可解析），降级保留上一稿: {}", round, e.getMessage());
                return current;
            }
        }
        return current;
    }

    /** 评审/重修入参序列化：失败不抛（提示词退化 toString，精修可用性优先于美观） */
    private static String toJson(Storyboard storyboard) {
        try {
            return JSON.writeValueAsString(storyboard);
        } catch (Exception e) {
            return String.valueOf(storyboard);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
