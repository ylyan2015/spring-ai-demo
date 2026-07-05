package com.github.ylyan2015.springaidemo.exception;

/**
 * 存储异常
 * 文件存储操作（上传/下载/删除）过程中发生错误时抛出
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
