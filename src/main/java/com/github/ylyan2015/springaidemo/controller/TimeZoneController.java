package com.github.ylyan2015.springaidemo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 时区控制器
 * 根据客户端IP获取时区信息
 */
@RestController
@RequestMapping("/api/timezone")
public class TimeZoneController {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // 简单缓存：IP -> 位置信息（时区+经纬度），避免频繁调用外部API
    private static final Map<String, CachedLocation> locationCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24小时

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTimezone(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        CachedLocation location = resolveLocation(clientIp);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("timezone", location.timezone);
        result.put("ip", clientIp);
        if (location.latitude != null) {
            result.put("latitude", location.latitude);
        }
        if (location.longitude != null) {
            result.put("longitude", location.longitude);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 从请求中提取客户端真实IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多个IP，取第一个
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        ip = request.getHeader("WL-Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    /**
     * 通过 ipinfo.io 解析IP获取时区和经纬度，带缓存
     */
    private CachedLocation resolveLocation(String ip) {
        // 检查缓存
        CachedLocation cached = locationCache.get(ip);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        // 本地/私有IP无法通过外部API解析，使用系统默认时区
        if (isPrivateIp(ip)) {
            CachedLocation loc = new CachedLocation(ZoneId.systemDefault().getId(), null, null);
            locationCache.put(ip, loc);
            return loc;
        }

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://ipinfo.io/" + ip + "/json"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String timezone = json.has("timezone") ? json.get("timezone").asText() : null;
                Double latitude = null;
                Double longitude = null;
                if (json.has("loc") && !json.get("loc").isNull()) {
                    String[] parts = json.get("loc").asText().split(",");
                    if (parts.length == 2) {
                        try {
                            latitude = Double.parseDouble(parts[0]);
                            longitude = Double.parseDouble(parts[1]);
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse loc for IP {}: {}", ip, json.get("loc").asText());
                        }
                    }
                }
                if (timezone != null && !timezone.isEmpty()) {
                    CachedLocation loc = new CachedLocation(timezone, latitude, longitude);
                    locationCache.put(ip, loc);
                    log.info("Resolved location for IP {}: tz={}, lat={}, lon={}", ip, timezone, latitude, longitude);
                    return loc;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve location for IP {}: {}", ip, e.getMessage());
        }

        // 降级：使用系统默认时区
        CachedLocation fallback = new CachedLocation(ZoneId.systemDefault().getId(), null, null);
        locationCache.put(ip, fallback);
        return fallback;
    }

    /**
     * 判断是否为私有/本地IP
     */
    private boolean isPrivateIp(String ip) {
        if (ip == null) return true;
        return ip.equals("127.0.0.1")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.equals("::1")
                || ip.equals("localhost")
                || ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.20.") || ip.startsWith("172.21.")
                || ip.startsWith("172.22.") || ip.startsWith("172.23.")
                || ip.startsWith("172.24.") || ip.startsWith("172.25.")
                || ip.startsWith("172.26.") || ip.startsWith("172.27.")
                || ip.startsWith("172.28.") || ip.startsWith("172.29.")
                || ip.startsWith("172.30.") || ip.startsWith("172.31.");
    }

    private static class CachedLocation {
        final String timezone;
        final Double latitude;
        final Double longitude;
        final long cachedAt;

        CachedLocation(String timezone, Double latitude, Double longitude) {
            this.timezone = timezone;
            this.latitude = latitude;
            this.longitude = longitude;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }
}
