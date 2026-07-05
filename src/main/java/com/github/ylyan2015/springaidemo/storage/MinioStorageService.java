package com.github.ylyan2015.springaidemo.storage;

import com.github.ylyan2015.springaidemo.config.StorageProperties;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import io.minio.*;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * MinIO 存储服务实现
 */
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final StorageProperties.Minio config;
    private MinioClient minioClient;

    public MinioStorageService(StorageProperties.Minio config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        try {
            this.minioClient = MinioClient.builder()
                    .endpoint(config.getEndpoint())
                    .credentials(config.getAccessKey(), config.getSecretKey())
                    .build();

            // 检查 bucket 是否存在，不存在则创建
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(config.getBucketName()).build());
            if (!bucketExists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(config.getBucketName()).build());
                log.info("✓ MinIO Bucket 已创建: {}", config.getBucketName());
            }
            log.info("✓ MinIO 客户端已初始化，Endpoint: {}", config.getEndpoint());
        } catch (Exception e) {
            throw new StorageException("MinIO 客户端初始化失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        // MinIO client doesn't need explicit shutdown in newer versions
        log.info("✓ MinIO 客户端已关闭");
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType) {
        try {
            String objectName = "images/" + java.time.LocalDate.now() + "/"
                    + java.util.UUID.randomUUID() + "_" + fileName;

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .stream(inputStream, -1, 10485760)
                            .contentType(contentType)
                            .build());
            log.info("✓ MinIO 文件已上传: {}/{}", config.getBucketName(), objectName);

            return objectName;
        } catch (Exception e) {
            throw new StorageException("MinIO 文件上传失败: " + fileName, e);
        }
    }

    @Override
    public InputStream download(String fileId) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(fileId)
                            .build());
        } catch (Exception e) {
            throw new StorageException("MinIO 文件下载失败: " + fileId, e);
        }
    }

    @Override
    public void delete(String fileId) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(fileId)
                            .build());
            log.info("✓ MinIO 文件已删除: {}/{}", config.getBucketName(), fileId);
        } catch (Exception e) {
            throw new StorageException("MinIO 文件删除失败: " + fileId, e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(fileId)
                            .build());
            return true;
        } catch (MinioException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getAccessUrl(String fileId) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(config.getBucketName())
                            .object(fileId)
                            .expiry(60 * 60 * 24) // 24小时有效期
                            .build());
        } catch (Exception e) {
            // 降级：返回拼接URL
            return config.getEndpoint() + "/" + config.getBucketName() + "/" + fileId;
        }
    }

    @Override
    public String getStorageType() {
        return "minio";
    }
}

