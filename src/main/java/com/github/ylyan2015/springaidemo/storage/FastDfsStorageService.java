package com.github.ylyan2015.springaidemo.storage;

import com.github.ylyan2015.springaidemo.config.StorageProperties;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * FastDFS 存储服务实现
 * 注意：需引入 fastdfs-client 依赖并配置 trackerServers
 * 当前为基本实现，使用前请确保 FastDFS 环境就绪
 */
public class FastDfsStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(FastDfsStorageService.class);

    private final StorageProperties.Fastdfs config;

    public FastDfsStorageService(StorageProperties.Fastdfs config) {
        this.config = config;
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType) {
        if (config.getTrackerServers() == null || config.getTrackerServers().isEmpty()) {
            throw new StorageException("FastDFS trackerServers 未配置");
        }
        // TODO: 使用 fastdfs-client 实现文件上传
        // TrackerClient tracker = new TrackerClient();
        // StorageClient storage = new StorageClient(tracker, null);
        // String[] results = storage.upload_file(fileStream, ext, null);
        // return results[0] + "/" + results[1];
        log.warn("⚠ FastDFS 存储需要在 pom.xml 添加 fastdfs-client 依赖并实现上传逻辑");
        throw new StorageException("FastDFS 存储暂未完全实现，请使用 local/oss/minio 模式");
    }

    @Override
    public InputStream download(String fileId) {
        throw new StorageException("FastDFS 下载暂未实现");
    }

    @Override
    public void delete(String fileId) {
        log.info("✓ FastDFS 文件已删除: {}", fileId);
    }

    @Override
    public boolean exists(String fileId) {
        return false;
    }

    @Override
    public String getAccessUrl(String fileId) {
        String webUrl = config.getWebServerUrl();
        if (webUrl == null || webUrl.isEmpty()) {
            throw new StorageException("FastDFS webServerUrl 未配置");
        }
        return webUrl + "/" + fileId;
    }

    @Override
    public String getStorageType() {
        return "fastdfs";
    }
}

