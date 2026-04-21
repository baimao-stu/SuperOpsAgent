package com.whitec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLS 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "cls")
public class ClsProperties {

    /**
     * 是否启用 Mock 日志
     */
    private boolean mockEnabled = false;

    /**
     * 默认地域
     */
    private String region = "ap-guangzhou";

    /**
     * 腾讯云 SecretId
     */
    private String secretId;

    /**
     * 腾讯云 SecretKey
     */
    private String secretKey;

    /**
     * 请求超时时间，单位秒
     */
    private int timeoutSeconds = 10;

    /**
     * 默认查询最近 N 分钟日志
     */
    private int defaultLookbackMinutes = 30;

    /**
     * 主题别名到 TopicId 的映射
     */
    private Map<String, String> topics = new LinkedHashMap<>();
}
