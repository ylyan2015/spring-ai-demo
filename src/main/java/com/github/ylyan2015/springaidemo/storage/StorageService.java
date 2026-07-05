package com.github.ylyan2015.springaidemo.storage;

import java.io.InputStream;

/**
 * 存储服务接口
 * 统一抽象层，支持多种存储方式（本地/OSS/MinIO/FastDFS）
 */
public interface StorageService {

    /**
     * 上传文件
     *
     * @param inputStream 文件输入流
     * @param fileName    文件名（含扩展名）
     * @param contentType 文件类型（如 image/png）
     * @return 文件标识（可用于后续下载/删除）
     */
    String upload(InputStream inputStream, String fileName, String contentType);

    /**
     * 下载文件
     *
     * @param fileId 文件标识
     * @return 文件输入流
     */
    InputStream download(String fileId);

    /**
     * 删除文件
     *
     * @param fileId 文件标识
     */
    void delete(String fileId);

    /**
     * 检查文件是否存在
     *
     * @param fileId 文件标识
     * @return 是否存在
     */
    boolean exists(String fileId);

    /**
     * 获取文件访问URL
     *
     * @param fileId 文件标识
     * @return 可公开访问的URL
     */
    String getAccessUrl(String fileId);

    /**
     * 获取存储类型名称
     *
     * @return 存储类型：local / oss / minio / fastdfs
     */
    String getStorageType();
}
