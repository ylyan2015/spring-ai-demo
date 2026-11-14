# ==================== Stage 1: Build ====================
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Cache dependencies first (faster rebuilds)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 短视频工作室运行依赖（模块 C/D/F）：
# - ffmpeg（含 ffprobe/xfade/libass/libx264/libmp3lame，Alpine 社区构建 ≥6.x）：视频合成/字幕烧录/BGM 混音
# - python3 + edge-tts（venv 隔离，避开 Alpine PEP 668 限制）：旁白配音
# - font-noto-cjk：字幕烧录中文字体（application-docker.yml 默认 Noto Sans CJK SC）
RUN apk add --no-cache ffmpeg python3 py3-pip font-noto-cjk \
    && python3 -m venv /opt/tts-venv \
    && /opt/tts-venv/bin/pip install --no-cache-dir edge-tts

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the built jar
COPY --from=build /app/target/*.jar app.jar

# 预置免版权 BGM 素材（启动自动扫描注册；.dockerignore 已放行 data/bgm/）
COPY data/bgm /app/data/bgm

# Create data directory for H2 fallback
RUN mkdir -p /app/data /app/uploads && chown -R appuser:appgroup /app/data /app/uploads

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m" \
    FFMPEG_PATH=/usr/bin/ffmpeg \
    FFPROBE_PATH=/usr/bin/ffprobe \
    PYTHON_CMD=/opt/tts-venv/bin/python

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
