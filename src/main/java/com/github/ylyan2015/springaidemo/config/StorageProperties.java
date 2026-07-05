package com.github.ylyan2015.springaidemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置属性
 * 通过 application.yml 中的 storage 前缀注入
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** 存储类型：local / oss / minio / fastdfs */
    private String type = "local";

    /** 本地存储配置 */
    private Local local = new Local();

    /** 阿里云OSS配置 */
    private Oss oss = new Oss();

    /** MinIO配置 */
    private Minio minio = new Minio();

    /** FastDFS配置 */
    private Fastdfs fastdfs = new Fastdfs();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Local getLocal() { return local; }
    public void setLocal(Local local) { this.local = local; }
    public Oss getOss() { return oss; }
    public void setOss(Oss oss) { this.oss = oss; }
    public Minio getMinio() { return minio; }
    public void setMinio(Minio minio) { this.minio = minio; }
    public Fastdfs getFastdfs() { return fastdfs; }
    public void setFastdfs(Fastdfs fastdfs) { this.fastdfs = fastdfs; }

    public static class Local {
        private String basePath = "./uploads";
        private String accessPath = "/images";

        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
        public String getAccessPath() { return accessPath; }
        public void setAccessPath(String accessPath) { this.accessPath = accessPath; }
    }

    public static class Oss {
        private String endpoint;
        private String accessKeyId;
        private String accessKeySecret;
        private String bucketName;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    }

    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey;
        private String secretKey;
        private String bucketName = "ai-images";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    }

    public static class Fastdfs {
        private String trackerServers;
        private String webServerUrl;

        public String getTrackerServers() { return trackerServers; }
        public void setTrackerServers(String trackerServers) { this.trackerServers = trackerServers; }
        public String getWebServerUrl() { return webServerUrl; }
        public void setWebServerUrl(String webServerUrl) { this.webServerUrl = webServerUrl; }
    }
}
