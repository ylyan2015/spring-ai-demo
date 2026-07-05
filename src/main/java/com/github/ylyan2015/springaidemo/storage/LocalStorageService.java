package com.github.ylyan2015.springaidemo.storage;

import com.github.ylyan2015.springaidemo.config.StorageProperties;
import com.github.ylyan2015.springaidemo.exception.StorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 本地存储服务实现
 * 文件保存到本地 ./uploads/ 目录，按日期分文件夹存储
 * 通过静态资源映射对外提供访问
 */
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final StorageProperties.Local config;
    private final Path basePath;

    public LocalStorageService(StorageProperties.Local config) {
        this.config = config;
        this.basePath = Paths.get(config.getBasePath()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(basePath);
            log.info("✓ 本地存储已初始化，根目录: {}", basePath);
        } catch (IOException e) {
            throw new StorageException("无法创建本地存储目录: " + basePath, e);
        }
    }

    @Override
    public String upload(InputStream inputStream, String fileName, String contentType) {
        try {
            // 按日期分文件夹：./uploads/2026/07/05/uuid_filename.ext
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            Path targetDir = basePath.resolve(dateDir);
            Files.createDirectories(targetDir);

            String uniqueName = java.util.UUID.randomUUID().toString() + "_" + fileName;
            Path targetFile = targetDir.resolve(uniqueName);

            Files.copy(inputStream, targetFile);
            long fileSize = Files.size(targetFile);
            log.info("✓ 本地文件已保存: {}, 大小: {} bytes", targetFile, fileSize);

            // 返回相对路径作为 fileId
            return dateDir + "/" + uniqueName;
        } catch (IOException e) {
            throw new StorageException("本地文件上传失败: " + fileName, e);
        }
    }

    @Override
    public InputStream download(String fileId) {
        try {
            Path file = basePath.resolve(fileId).normalize();
            if (!file.startsWith(basePath)) {
                throw new StorageException("非法的文件路径: " + fileId);
            }
            if (!Files.exists(file)) {
                throw new StorageException("文件不存在: " + fileId);
            }
            return new BufferedInputStream(Files.newInputStream(file));
        } catch (IOException e) {
            throw new StorageException("本地文件下载失败: " + fileId, e);
        }
    }

    @Override
    public void delete(String fileId) {
        try {
            Path file = basePath.resolve(fileId).normalize();
            if (!file.startsWith(basePath)) {
                throw new StorageException("非法的文件路径: " + fileId);
            }
            Files.deleteIfExists(file);
            log.info("✓ 本地文件已删除: {}", file);
        } catch (IOException e) {
            throw new StorageException("本地文件删除失败: " + fileId, e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        Path file = basePath.resolve(fileId).normalize();
        if (!file.startsWith(basePath)) {
            return false;
        }
        return Files.exists(file);
    }

    @Override
    public String getAccessUrl(String fileId) {
        // 本地文件通过静态资源映射访问：/images/2026/07/05/uuid_filename.ext
        return config.getAccessPath() + "/" + fileId.replace("\\", "/");
    }

    @Override
    public String getStorageType() {
        return "local";
    }
}

