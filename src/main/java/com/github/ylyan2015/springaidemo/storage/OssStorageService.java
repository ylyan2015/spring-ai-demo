package com.github.ylyan2015.springaidemo.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.github.ylyan2015.springaidemo.config.StorageProperties;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * 阿里云 OSS 存储服务实现
 */
public class OssStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(OssStorageService.class);

    private final StorageProperties.Oss config;
    private OSS ossClient;

    public OssStorageService(StorageProperties.Oss config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        if (config.getEndpoint() == null || config.getEndpoint().isEmpty()) {
            log.warn("⚠ OSS 未配置 endpoint，OSS 存储不可用");
            return;
        }
        this.ossClient = new OSSClientBuilder().build(
                config.getEndpoint(),
                config.getAccessKeyId(),
                config.getAccessKeySecret()
        );
        log.info("✓ OSS 客户端已初始化，Endpoint: {}", config.getEndpoint());
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("✓ OSS 客户端已关闭");
        }
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType) {
        if (ossClient == null) {
            throw new StorageException("OSS 客户端未初始化");
        }
        try {
            String objectName = "images/" + java.time.LocalDate.now() + "/"
                    + java.util.UUID.randomUUID() + "_" + fileName;

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);

            ossClient.putObject(config.getBucketName(), objectName, inputStream, metadata);
            log.info("✓ OSS 文件已上传: {}/{}", config.getBucketName(), objectName);

            return objectName;
        } catch (Exception e) {
            throw new StorageException("OSS 文件上传失败: " + fileName, e);
        }
    }

    @Override
    public InputStream download(String fileId) {
        if (ossClient == null) {
            throw new StorageException("OSS 客户端未初始化");
        }
        try {
            return ossClient.getObject(config.getBucketName(), fileId).getObjectContent();
        } catch (Exception e) {
            throw new StorageException("OSS 文件下载失败: " + fileId, e);
        }
    }

    @Override
    public void delete(String fileId) {
        if (ossClient == null) {
            throw new StorageException("OSS 客户端未初始化");
        }
        try {
            ossClient.deleteObject(config.getBucketName(), fileId);
            log.info("✓ OSS 文件已删除: {}/{}", config.getBucketName(), fileId);
        } catch (Exception e) {
            throw new StorageException("OSS 文件删除失败: " + fileId, e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        if (ossClient == null) return false;
        try {
            return ossClient.doesObjectExist(config.getBucketName(), fileId);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getAccessUrl(String fileId) {
        return "https://" + config.getBucketName() + "." + config.getEndpoint() + "/" + fileId;
    }

    @Override
    public String getStorageType() {
        return "oss";
    }
}

