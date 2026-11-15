package com.github.ylyan2015.springaidemo.video.generator;

import org.springframework.stereotype.Component;

/**
 * 空视频生成器（video.generator=none 默认）：合成走静图 Ken Burns 回退，存量行为零变化
 */
@Component
public class NoneVideoGenerator implements VideoGenerator {

    @Override
    public GeneratedVideo generate(VideoGenerationRequest request) {
        throw new RuntimeException("video.generator=none：未配置图生视频，合成将使用静图运镜回退");
    }

    @Override
    public String getType() {
        return "none";
    }

    @Override
    public String getName() {
        return "静图运镜（无 i2v）";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}