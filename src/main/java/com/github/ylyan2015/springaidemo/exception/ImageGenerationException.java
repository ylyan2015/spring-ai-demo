package com.github.ylyan2015.springaidemo.exception;

/**
 * 图像生成异常
 * AI 模型调用过程中发生错误时抛出
 */
public class ImageGenerationException extends RuntimeException {

    public ImageGenerationException(String message) {
        super(message);
    }

    public ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
