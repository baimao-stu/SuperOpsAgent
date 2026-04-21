package com.whitec.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.LogInfo;
import com.tencentcloudapi.cls.v20201016.models.SearchLogRequest;
import com.tencentcloudapi.cls.v20201016.models.SearchLogResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.whitec.config.ClsProperties;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具
 * 用于查询 CLS（云日志服务）的日志信息
 * 支持 Mock 模式，提供与告警关联的模拟日志数据
 */
@Component
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ClsProperties clsProperties;

    private ClsClient clsClient;
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    
    @jakarta.annotation.PostConstruct
    public void init() {
        if (!clsProperties.isMockEnabled() && hasRealClsCredentials()) {
            this.clsClient = createClsClient();
        }

        logger.info("✅ QueryLogsTools 初始化成功, Mock模式: {}, 默认地域: {}, 已配置主题数: {}",
                clsProperties.isMockEnabled(), clsProperties.getRegion(), clsProperties.getTopics().size());
    }
    
    /**
     * 获取可用的日志主题列表
     * 用于查询前先了解有哪些日志主题可供查询
     */
    @Tool(description = "Get all available log topics and their descriptions. " +
            "Call this tool first before querying logs to understand what log topics are available. " +
            "Returns a list of log topics with their names, descriptions, and example queries.")
    public String getAvailableLogTopics() {
        logger.info("获取可用的日志主题列表");
        
        try {
            List<LogTopicInfo> topics = new ArrayList<>();
            
            // 系统指标日志
            LogTopicInfo systemMetrics = new LogTopicInfo();
            systemMetrics.setTopicName("system-metrics");
            systemMetrics.setDescription("系统指标日志，包含 CPU、内存、磁盘使用率等系统资源监控数据");
            systemMetrics.setTopicId(resolveConfiguredTopicId("system-metrics"));
            systemMetrics.setExampleQueries(List.of(
                    "cpu_usage:>80",
                    "memory_usage:>85",
                    "disk_usage:>90",
                    "level:WARN AND service:payment-service"
            ));
            systemMetrics.setRelatedAlerts(List.of("HighCPUUsage", "HighMemoryUsage", "HighDiskUsage"));
            topics.add(systemMetrics);
            
            // 应用日志
            LogTopicInfo applicationLogs = new LogTopicInfo();
            applicationLogs.setTopicName("application-logs");
            applicationLogs.setDescription("应用日志，包含应用程序的错误日志、警告日志、慢请求日志、下游依赖调用日志等");
            applicationLogs.setTopicId(resolveConfiguredTopicId("application-logs"));
            applicationLogs.setExampleQueries(List.of(
                    "level:ERROR",
                    "level:FATAL",
                    "http_status:500",
                    "response_time:>3000",
                    "slow",
                    "downstream OR redis OR database OR mq"
            ));
            applicationLogs.setRelatedAlerts(List.of("ServiceUnavailable", "SlowResponse", "HighMemoryUsage"));
            topics.add(applicationLogs);
            
            // 数据库慢查询日志
            LogTopicInfo dbSlowQuery = new LogTopicInfo();
            dbSlowQuery.setTopicName("database-slow-query");
            dbSlowQuery.setDescription("数据库慢查询日志，包含执行时间较长的 SQL 查询，可用于分析数据库性能问题");
            dbSlowQuery.setTopicId(resolveConfiguredTopicId("database-slow-query"));
            dbSlowQuery.setExampleQueries(List.of(
                    "query_time:>2",
                    "table:orders",
                    "query_type:SELECT",
                    "*"  // 查询所有慢查询
            ));
            dbSlowQuery.setRelatedAlerts(List.of("SlowResponse", "ServiceUnavailable"));
            topics.add(dbSlowQuery);
            
            // 系统事件日志
            LogTopicInfo systemEvents = new LogTopicInfo();
            systemEvents.setTopicName("system-events");
            systemEvents.setDescription("系统事件日志，包含 Kubernetes Pod 重启、OOM Kill、容器崩溃等系统级事件");
            systemEvents.setTopicId(resolveConfiguredTopicId("system-events"));
            systemEvents.setExampleQueries(List.of(
                    "restart OR crash",
                    "oom_kill",
                    "event_type:PodRestart",
                    "reason:OOMKilled"
            ));
            systemEvents.setRelatedAlerts(List.of("ServiceUnavailable", "HighMemoryUsage"));
            topics.add(systemEvents);
            
            // 构建输出
            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(List.of("ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"));
            output.setDefaultRegion(resolveRegion(null));

            output.setMessage(String.format(
                    "共有 %d 个可用的日志主题。当前模式: %s，建议使用默认地域 '%s' 或省略 region 参数",
                    topics.size(),
                    clsProperties.isMockEnabled() ? "mock" : "real",
                    resolveRegion(null)));
            
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            
        } catch (Exception e) {
            logger.error("获取日志主题列表失败", e);
            return "{\"success\":false,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 查询日志
     * 从云日志服务查询指定条件的日志
     * 
     * @param region 地域，如 ap-guangzhou
     * @param logTopic 日志主题，如 system-metrics, application-logs
     * @param query 查询条件，如 level:ERROR OR cpu_usage:>80
     * @param limit 返回的日志条数，默认20条
     */
    // 有效地域列表
    private static final List<String> VALID_REGIONS = List.of(
            "ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );
    
    @Tool(description = "Query logs from Cloud Log Service (CLS). " +
            "Use this tool to search application logs, system metrics, and other log data. " +
            "IMPORTANT: Before calling this tool, you should call getAvailableLogTopics to understand what log topics are available. " +
            "Available log topics: " +
            "1) 'system-metrics' - System metrics logs (CPU, memory, disk usage, etc. Related to HighCPUUsage, HighMemoryUsage, HighDiskUsage alerts); " +
            "2) 'application-logs' - Application logs (error logs, slow request logs, downstream dependency logs. Related to ServiceUnavailable, SlowResponse alerts); " +
            "3) 'database-slow-query' - Database slow query logs (SQL queries with long execution time. Related to SlowResponse alerts); " +
            "4) 'system-events' - System event logs (Pod restart, OOM Kill, container crash. Related to ServiceUnavailable, HighMemoryUsage alerts). " +
            "logTopic (required, one of the above topics or their CLS topicId), " +
            "query (optional, defaults to a curated search if empty), " +
            "limit (optional, default 20, max 100).")
    public String queryLogs(
            @ToolParam(description = "地域，可选值: ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu。默认 ap-guangzhou") String region,
            @ToolParam(description = "日志主题，如 system-metrics, application-logs, database-slow-query, system-events，也支持 CLS TopicId") String logTopic,
            @ToolParam(description = "查询条件，支持 Lucene 语法，如 level:ERROR OR cpu_usage:>80；为空时返回该主题近 5 条核心日志") String query,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        String resolvedRegion = resolveRegion(region);
        String safeTopic = logTopic == null ? "" : logTopic.trim();
        String safeQuery = query == null ? "" : query;

        try {
            List<LogEntry> logEntries;
            
            if (clsProperties.isMockEnabled()) {
                // Mock 模式：返回与告警关联的模拟日志数据
                logEntries = buildMockLogs(resolvedRegion, safeTopic, safeQuery, actualLimit);
                logger.info("使用 Mock 数据，返回 {} 条日志", logEntries.size());
            } else {
                logEntries = fetchRealLogs(resolvedRegion, safeTopic, safeQuery, actualLimit);
                logger.info("使用真实 CLS 数据，返回 {} 条日志", logEntries.size());
            }
            
            // 构建成功响应
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(!logEntries.isEmpty());
            output.setRegion(resolvedRegion);
            output.setLogTopic(safeTopic);
            output.setQuery(resolveEffectiveQuery(safeTopic, safeQuery));
            output.setLogs(logEntries);
            output.setTotal(logEntries.size());
            output.setMessage(logEntries.isEmpty() ? "未找到匹配的日志" : String.format("成功查询到 %d 条日志", logEntries.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("日志查询完成: 找到 {} 条日志", logEntries.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询日志失败", e);
            return buildErrorResponse("查询失败: " + e.getMessage());
        }
    }

    private boolean hasRealClsCredentials() {
        return notBlank(clsProperties.getSecretId()) && notBlank(clsProperties.getSecretKey());
    }

    private ClsClient createClsClient() {
        Credential credential = new Credential(clsProperties.getSecretId(), clsProperties.getSecretKey());

        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("cls.tencentcloudapi.com");
        httpProfile.setConnTimeout((int) Duration.ofSeconds(clsProperties.getTimeoutSeconds()).toSeconds());

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new ClsClient(credential, clsProperties.getRegion(), clientProfile);
    }

    private List<LogEntry> fetchRealLogs(String region, String logTopic, String query, int limit) throws Exception {
        if (!hasRealClsCredentials()) {
            throw new IllegalStateException("CLS 凭证未配置，请设置 cls.secret-id / cls.secret-key 或环境变量 TENCENT_SECRET_ID / TENCENT_SECRET_KEY");
        }

        if (clsClient == null) {
            clsClient = createClsClient();
        }

        String topicId = resolveTopicId(logTopic);
        if (!notBlank(topicId)) {
            throw new IllegalArgumentException("日志主题未配置 TopicId: " + logTopic + "，请在 cls.topics 中配置映射，或直接传入真实 TopicId");
        }

        String effectiveQuery = resolveEffectiveQuery(logTopic, query);
        Instant now = Instant.now();
        Instant from = now.minus(clsProperties.getDefaultLookbackMinutes(), ChronoUnit.MINUTES);

        SearchLogRequest request = new SearchLogRequest();
        request.setFrom(from.getEpochSecond());
        request.setTo(now.getEpochSecond());
        request.setTopicId(topicId);
        request.setQueryString(effectiveQuery);
        request.setQuery(effectiveQuery);
        request.setQuerySyntax(1L);
        request.setSyntaxRule(1L);
        request.setSort("desc");
        request.setLimit((long) limit);
        request.setOffset(0L);
        request.setUseNewAnalysis(Boolean.TRUE);
        request.setHighLight(Boolean.TRUE);

        try {
            SearchLogResponse response = clsClient.SearchLog(request);
            LogInfo[] results = response.getResults();
            if (results == null || results.length == 0) {
                return Collections.emptyList();
            }

            List<LogEntry> entries = new ArrayList<>(Math.min(results.length, limit));
            for (LogInfo result : results) {
                entries.add(toLogEntry(result, region, logTopic));
            }
            return entries;
        } catch (TencentCloudSDKException e) {
            logger.error("CLS 查询失败, region: {}, topic: {}, query: {}", region, logTopic, effectiveQuery, e);
            throw new IllegalStateException("CLS 查询失败: " + e.getMessage(), e);
        }
    }

    private LogEntry toLogEntry(LogInfo logInfo, String region, String logTopic) {
        Map<String, String> parsedFields = parseLogFields(logInfo.getLogJson());
        LinkedHashMap<String, String> metrics = buildMetrics(logInfo, parsedFields, region, logTopic);

        LogEntry entry = new LogEntry();
        entry.setTimestamp(formatEpochSeconds(logInfo.getTime()));
        entry.setLevel(firstNonBlank(
                parsedFields.get("level"),
                parsedFields.get("severity"),
                parsedFields.get("loglevel"),
                "INFO"));
        entry.setService(firstNonBlank(
                parsedFields.get("service"),
                parsedFields.get("service_name"),
                parsedFields.get("app"),
                parsedFields.get("application"),
                logInfo.getTopicName(),
                "unknown-service"));
        entry.setInstance(firstNonBlank(
                parsedFields.get("instance"),
                parsedFields.get("pod"),
                parsedFields.get("host"),
                parsedFields.get("hostname"),
                logInfo.getHostName(),
                logInfo.getSource(),
                "unknown-instance"));
        entry.setMessage(resolveMessage(logInfo, parsedFields));
        entry.setMetrics(metrics);
        return entry;
    }

    private LinkedHashMap<String, String> buildMetrics(LogInfo logInfo, Map<String, String> parsedFields, String region, String logTopic) {
        LinkedHashMap<String, String> metrics = new LinkedHashMap<>();
        putIfNotBlank(metrics, "region", region);
        putIfNotBlank(metrics, "topic", logTopic);
        putIfNotBlank(metrics, "topic_id", logInfo.getTopicId());
        putIfNotBlank(metrics, "topic_name", logInfo.getTopicName());
        putIfNotBlank(metrics, "source", logInfo.getSource());
        putIfNotBlank(metrics, "host_name", logInfo.getHostName());
        putIfNotBlank(metrics, "file_name", logInfo.getFileName());

        for (Map.Entry<String, String> entry : parsedFields.entrySet()) {
            String key = entry.getKey();
            if (isReservedField(key)) {
                continue;
            }
            putIfNotBlank(metrics, key, entry.getValue());
            if (metrics.size() >= 12) {
                break;
            }
        }

        if (metrics.isEmpty()) {
            putIfNotBlank(metrics, "raw_log", logInfo.getRawLog());
        }
        return metrics;
    }

    private Map<String, String> parseLogFields(String logJson) {
        if (!notBlank(logJson)) {
            return Collections.emptyMap();
        }

        try {
            JsonNode root = objectMapper.readTree(logJson);
            if (!root.isObject()) {
                return Collections.emptyMap();
            }

            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    return;
                }
                if (value.isValueNode()) {
                    fields.put(entry.getKey(), value.asText());
                } else {
                    fields.put(entry.getKey(), value.toString());
                }
            });
            return fields;
        } catch (Exception e) {
            logger.debug("解析 CLS LogJson 失败，按原始日志处理: {}", logJson, e);
            return Collections.emptyMap();
        }
    }

    private String resolveMessage(LogInfo logInfo, Map<String, String> parsedFields) {
        String message = firstNonBlank(
                parsedFields.get("message"),
                parsedFields.get("msg"),
                parsedFields.get("content"),
                parsedFields.get("__content__"),
                parsedFields.get("__rawlog__"),
                parsedFields.get("raw_log"),
                logInfo.getRawLog());
        if (notBlank(message)) {
            return message;
        }
        if (notBlank(logInfo.getLogJson())) {
            return logInfo.getLogJson();
        }
        return "CLS 日志内容为空";
    }

    private String resolveTopicId(String logTopic) {
        if (!notBlank(logTopic)) {
            return resolveConfiguredTopicId("application-logs");
        }

        String configured = resolveConfiguredTopicId(logTopic);
        return notBlank(configured) ? configured : logTopic;
    }

    private String resolveConfiguredTopicId(String alias) {
        if (!notBlank(alias) || clsProperties.getTopics() == null) {
            return null;
        }
        return clsProperties.getTopics().get(alias);
    }

    private String resolveEffectiveQuery(String logTopic, String query) {
        if (notBlank(query)) {
            return query.trim();
        }

        String safeTopic = logTopic == null ? "" : logTopic.trim().toLowerCase();
        return switch (safeTopic) {
            case "system-metrics" -> "cpu_usage:>80 OR memory_usage:>85 OR disk_usage:>90";
            case "database-slow-query" -> "query_time:>2";
            case "system-events" -> "restart OR crash OR oom OR OOMKilled";
            case "application-logs" -> "level:ERROR OR slow OR timeout OR exception";
            default -> "*";
        };
    }

    private String resolveRegion(String region) {
        String candidate = notBlank(region) ? region.trim() : clsProperties.getRegion();
        if (!VALID_REGIONS.contains(candidate)) {
            logger.warn("收到非预置地域 {}, 自动回退到默认地域 {}", candidate, clsProperties.getRegion());
            return clsProperties.getRegion();
        }
        return candidate;
    }

    private String formatEpochSeconds(Long epochSeconds) {
        if (epochSeconds == null) {
            return FORMATTER.format(Instant.now());
        }
        return FORMATTER.format(Instant.ofEpochSecond(epochSeconds));
    }

    private boolean isReservedField(String key) {
        if (!notBlank(key)) {
            return true;
        }
        String normalized = key.toLowerCase();
        return normalized.equals("message")
                || normalized.equals("msg")
                || normalized.equals("content")
                || normalized.equals("__content__")
                || normalized.equals("__rawlog__")
                || normalized.equals("raw_log")
                || normalized.equals("service")
                || normalized.equals("service_name")
                || normalized.equals("app")
                || normalized.equals("application")
                || normalized.equals("instance")
                || normalized.equals("pod")
                || normalized.equals("host")
                || normalized.equals("hostname")
                || normalized.equals("level")
                || normalized.equals("severity")
                || normalized.equals("loglevel");
    }

    private void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (notBlank(key) && notBlank(value)) {
            target.putIfAbsent(key, value);
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (notBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 构建 Mock 日志数据

     * 根据日志主题和查询条件返回与告警关联的模拟数据
     */
    private List<LogEntry> buildMockLogs(String region, String logTopic, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        Instant now = Instant.now();
        
        String safeTopic = logTopic == null ? "system-metrics" : logTopic.toLowerCase();
        String normalizedQuery = query == null ? "" : query.toLowerCase();
        
        // 根据日志主题和查询条件生成对应的 mock 数据
        switch (safeTopic) {

            case "system-metrics":
                logs.addAll(buildSystemMetricsLogs(now, normalizedQuery, limit));
                break;
            case "application-logs":
                logs.addAll(buildApplicationLogs(now, normalizedQuery, limit));
                break;
            case "database-slow-query":
                logs.addAll(buildDatabaseSlowQueryLogs(now, normalizedQuery, limit));
                break;
            case "system-events":
                logs.addAll(buildSystemEventsLogs(now, normalizedQuery, limit));
                break;
            default:
                logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        }
        
        if (logs.isEmpty()) {
            logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        }
        
        // 限制返回条数

        if (logs.size() > limit) {
            logs = logs.subList(0, limit);
        }
        
        return logs;
    }
    
    /**
     * 构建系统指标日志（与 CPU、内存、磁盘告警关联）
     */
    private List<LogEntry> buildSystemMetricsLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        // CPU 相关日志
        if (query.contains("cpu") || query.contains(">80")) {

            for (int i = 0; i < 5; i++) {
                LogEntry log = new LogEntry();
                log.setTimestamp(FORMATTER.format(now.minus(i * 2, ChronoUnit.MINUTES)));
                log.setLevel("WARN");
                log.setService("payment-service");
                log.setInstance("pod-payment-service-7d8f9c6b5-x2k4m");
                log.setMessage(String.format("CPU使用率过高: %.1f%%, 进程: java (PID: 1), 线程数: 245", 92.0 - i * 1.5));
                log.setMetrics(Map.of(
                        "cpu_usage", String.format("%.1f", 92.0 - i * 1.5),
                        "cpu_cores", "4",
                        "load_average_1m", "3.82",
                        "load_average_5m", "3.65",
                        "top_process", "java",
                        "process_threads", "245"
                ));
                logs.add(log);
            }
        }
        
        // 内存相关日志
        if (query.contains("memory") || query.contains(">85") || query.contains("oom")) {

            for (int i = 0; i < 5; i++) {
                LogEntry log = new LogEntry();
                log.setTimestamp(FORMATTER.format(now.minus(i * 3, ChronoUnit.MINUTES)));
                log.setLevel("WARN");
                log.setService("order-service");
                log.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
                log.setMessage(String.format("内存使用率过高: %.1f%%, JVM堆内存: %.1fGB/4GB, GC次数: %d", 
                        91.0 - i * 1.2, 3.8 - i * 0.1, 128 - i * 5));
                log.setMetrics(Map.of(
                        "memory_usage", String.format("%.1f", 91.0 - i * 1.2),
                        "jvm_heap_used", String.format("%.1fGB", 3.8 - i * 0.1),
                        "jvm_heap_max", "4GB",
                        "gc_count", String.valueOf(128 - i * 5),
                        "gc_time_ms", String.valueOf(1250 + i * 50)
                ));
                logs.add(log);
            }
            
            // 添加 GC 警告日志
            LogEntry gcLog = new LogEntry();
            gcLog.setTimestamp(FORMATTER.format(now.minus(8, ChronoUnit.MINUTES)));
            gcLog.setLevel("WARN");
            gcLog.setService("order-service");
            gcLog.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
            gcLog.setMessage("频繁 Full GC 警告: 过去10分钟内发生 15 次 Full GC, 平均耗时 850ms, 建议检查内存泄漏");
            gcLog.setMetrics(Map.of(
                    "full_gc_count", "15",
                    "avg_gc_time_ms", "850",
                    "survivor_space", "95%",
                    "old_gen", "89%"
            ));
            logs.add(gcLog);
        }
        
        // 磁盘相关日志
        if (query.contains("disk") || query.contains("filesystem")) {

            for (int i = 0; i < 3; i++) {
                LogEntry log = new LogEntry();
                log.setTimestamp(FORMATTER.format(now.minus(i * 5, ChronoUnit.MINUTES)));
                log.setLevel("WARN");
                log.setService("log-collector");
                log.setInstance("node-worker-01");
                log.setMessage(String.format("磁盘使用率告警: /data 分区使用率 %.1f%%, 可用空间: %.1fGB", 
                        85.0 + i * 2, 15.0 - i * 2));
                log.setMetrics(Map.of(
                        "disk_usage", String.format("%.1f%%", 85.0 + i * 2),
                        "disk_available", String.format("%.1fGB", 15.0 - i * 2),
                        "disk_total", "100GB",
                        "mount_point", "/data",
                        "largest_dir", "/data/logs"
                ));
                logs.add(log);
            }
        }
        
        return logs;
    }
    
    /**
     * 构建应用日志（与服务不可用、慢响应告警关联）
     */
    private List<LogEntry> buildApplicationLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        // ERROR 级别日志
        if (query.contains("error") || query.contains("fatal") || query.contains("500")) {

            // 数据库连接错误
            LogEntry dbError = new LogEntry();
            dbError.setTimestamp(FORMATTER.format(now.minus(5, ChronoUnit.MINUTES)));
            dbError.setLevel("ERROR");
            dbError.setService("order-service");
            dbError.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
            dbError.setMessage("数据库连接池耗尽: Cannot acquire connection from pool, " +
                    "active: 50/50, waiting: 23, timeout: 30000ms");
            dbError.setMetrics(Map.of(
                    "error_type", "ConnectionPoolExhaustedException",
                    "pool_active", "50",
                    "pool_max", "50",
                    "waiting_threads", "23"
            ));
            logs.add(dbError);
            
            // OOM 错误
            LogEntry oomError = new LogEntry();
            oomError.setTimestamp(FORMATTER.format(now.minus(12, ChronoUnit.MINUTES)));
            oomError.setLevel("FATAL");
            oomError.setService("order-service");
            oomError.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
            oomError.setMessage("java.lang.OutOfMemoryError: Java heap space at " +
                    "com.example.order.service.OrderService.processLargeOrder(OrderService.java:156)");
            oomError.setMetrics(Map.of(
                    "error_type", "OutOfMemoryError",
                    "heap_used", "3.9GB",
                    "heap_max", "4GB",
                    "stack_trace", "OrderService.processLargeOrder -> OrderRepository.findByCondition -> HikariPool.getConnection"
            ));
            logs.add(oomError);
            
            // HTTP 500 错误
            for (int i = 0; i < 3; i++) {
                LogEntry httpError = new LogEntry();
                httpError.setTimestamp(FORMATTER.format(now.minus(3 + i, ChronoUnit.MINUTES)));
                httpError.setLevel("ERROR");
                httpError.setService("user-service");
                httpError.setInstance("pod-user-service-8e9f0a1b2-k5j6h");
                httpError.setMessage(String.format("HTTP 500 Internal Server Error: /api/v1/users/profile, " +
                        "耗时: %dms, 错误: Database query timeout", 5200 + i * 300));
                httpError.setMetrics(Map.of(
                        "http_status", "500",
                        "uri", "/api/v1/users/profile",
                        "method", "GET",
                        "duration_ms", String.valueOf(5200 + i * 300),
                        "error_cause", "QueryTimeoutException"
                ));
                logs.add(httpError);
            }
        }
        
        // 慢响应相关日志
        if (query.contains("response_time") || query.contains("slow") || query.contains(">3000")) {

            for (int i = 0; i < 5; i++) {
                LogEntry slowLog = new LogEntry();
                slowLog.setTimestamp(FORMATTER.format(now.minus(i * 2, ChronoUnit.MINUTES)));
                slowLog.setLevel("WARN");
                slowLog.setService("user-service");
                slowLog.setInstance("pod-user-service-8e9f0a1b2-k5j6h");
                slowLog.setMessage(String.format("慢请求警告: %s, 响应时间: %dms, 阈值: 3000ms",
                        i % 2 == 0 ? "/api/v1/users/profile" : "/api/v1/users/orders",
                        4200 - i * 150));
                slowLog.setMetrics(Map.of(
                        "uri", i % 2 == 0 ? "/api/v1/users/profile" : "/api/v1/users/orders",
                        "response_time_ms", String.valueOf(4200 - i * 150),
                        "threshold_ms", "3000",
                        "db_time_ms", String.valueOf(3800 - i * 100),
                        "cache_hit", "false"
                ));
                logs.add(slowLog);
            }
        }
        
        // 下游服务依赖相关日志
        if (query.contains("downstream") || query.contains("redis") || 
            query.contains("database") || query.contains("mq")) {

            LogEntry redisError = new LogEntry();
            redisError.setTimestamp(FORMATTER.format(now.minus(7, ChronoUnit.MINUTES)));
            redisError.setLevel("ERROR");
            redisError.setService("payment-service");
            redisError.setInstance("pod-payment-service-7d8f9c6b5-x2k4m");
            redisError.setMessage("Redis 连接超时: 无法连接到 Redis 集群, 节点: redis-cluster-01:6379, 超时: 3000ms");
            redisError.setMetrics(Map.of(
                    "dependency", "redis",
                    "host", "redis-cluster-01:6379",
                    "timeout_ms", "3000",
                    "retry_count", "3"
            ));
            logs.add(redisError);
            
            LogEntry mqError = new LogEntry();
            mqError.setTimestamp(FORMATTER.format(now.minus(9, ChronoUnit.MINUTES)));
            mqError.setLevel("WARN");
            mqError.setService("order-service");
            mqError.setInstance("pod-order-service-5c7d8e9f1-m3n2p");
            mqError.setMessage("消息队列积压警告: 队列 order-process-queue 积压消息数: 15823, 消费速率下降");
            mqError.setMetrics(Map.of(
                    "dependency", "rabbitmq",
                    "queue", "order-process-queue",
                    "pending_messages", "15823",
                    "consumer_count", "3"
            ));
            logs.add(mqError);
        }
        
        return logs;
    }
    
    /**
     * 构建数据库慢查询日志（与慢响应告警关联）
     */
    private List<LogEntry> buildDatabaseSlowQueryLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        // 慢查询日志
        LogEntry slowQuery1 = new LogEntry();
        slowQuery1.setTimestamp(FORMATTER.format(now.minus(3, ChronoUnit.MINUTES)));
        slowQuery1.setLevel("WARN");
        slowQuery1.setService("mysql");
        slowQuery1.setInstance("mysql-primary-01");
        slowQuery1.setMessage("慢查询: SELECT * FROM orders WHERE user_id = ? AND status IN (?, ?, ?) " +
                "ORDER BY created_at DESC LIMIT 100, 执行时间: 3.2s, 扫描行数: 1,245,678");
        slowQuery1.setMetrics(Map.of(
                "query_time_sec", "3.2",
                "rows_examined", "1245678",
                "rows_returned", "100",
                "index_used", "idx_user_id",
                "table", "orders",
                "query_type", "SELECT"
        ));
        logs.add(slowQuery1);
        
        LogEntry slowQuery2 = new LogEntry();
        slowQuery2.setTimestamp(FORMATTER.format(now.minus(6, ChronoUnit.MINUTES)));
        slowQuery2.setLevel("WARN");
        slowQuery2.setService("mysql");
        slowQuery2.setInstance("mysql-primary-01");
        slowQuery2.setMessage("慢查询: SELECT u.*, p.* FROM users u LEFT JOIN user_profiles p ON u.id = p.user_id " +
                "WHERE u.last_login > ?, 执行时间: 2.8s, 全表扫描");
        slowQuery2.setMetrics(Map.of(
                "query_time_sec", "2.8",
                "rows_examined", "856234",
                "rows_returned", "45678",
                "index_used", "NONE",
                "table", "users, user_profiles",
                "query_type", "SELECT",
                "warning", "Full table scan detected"
        ));
        logs.add(slowQuery2);
        
        LogEntry slowQuery3 = new LogEntry();
        slowQuery3.setTimestamp(FORMATTER.format(now.minus(8, ChronoUnit.MINUTES)));
        slowQuery3.setLevel("WARN");
        slowQuery3.setService("mysql");
        slowQuery3.setInstance("mysql-primary-01");
        slowQuery3.setMessage("慢查询: UPDATE orders SET status = ? WHERE created_at < ? AND status = ?, " +
                "执行时间: 4.5s, 锁等待时间: 2.1s");
        slowQuery3.setMetrics(Map.of(
                "query_time_sec", "4.5",
                "lock_time_sec", "2.1",
                "rows_affected", "23456",
                "table", "orders",
                "query_type", "UPDATE",
                "warning", "High lock contention"
        ));
        logs.add(slowQuery3);
        
        return logs;
    }
    
    /**
     * 构建系统事件日志（与服务不可用告警关联）
     */
    private List<LogEntry> buildSystemEventsLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        // 服务重启事件
        if (query.contains("restart") || query.contains("crash") || 
            query.contains("oom_kill")) {

            LogEntry restartEvent = new LogEntry();
            restartEvent.setTimestamp(FORMATTER.format(now.minus(15, ChronoUnit.MINUTES)));
            restartEvent.setLevel("WARN");
            restartEvent.setService("kubernetes");
            restartEvent.setInstance("kube-controller-manager");
            restartEvent.setMessage("Pod 重启事件: pod-order-service-5c7d8e9f1-m3n2p, 原因: OOMKilled, " +
                    "容器退出码: 137, 重启次数: 3");
            restartEvent.setMetrics(Map.of(
                    "event_type", "PodRestart",
                    "pod", "pod-order-service-5c7d8e9f1-m3n2p",
                    "reason", "OOMKilled",
                    "exit_code", "137",
                    "restart_count", "3",
                    "namespace", "production"
            ));
            logs.add(restartEvent);
            
            LogEntry oomKillEvent = new LogEntry();
            oomKillEvent.setTimestamp(FORMATTER.format(now.minus(16, ChronoUnit.MINUTES)));
            oomKillEvent.setLevel("ERROR");
            oomKillEvent.setService("kernel");
            oomKillEvent.setInstance("node-worker-02");
            oomKillEvent.setMessage("OOM Killer 触发: 进程 java (PID: 12345) 被杀死, " +
                    "内存使用: 3.9GB, 内存限制: 4GB");
            oomKillEvent.setMetrics(Map.of(
                    "event_type", "OOMKill",
                    "process", "java",
                    "pid", "12345",
                    "memory_used", "3.9GB",
                    "memory_limit", "4GB",
                    "cgroup", "/kubepods/pod-order-service"
            ));
            logs.add(oomKillEvent);
        }
        
        return logs;
    }
    
    /**
     * 构建通用日志
     */
    private List<LogEntry> buildGenericLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        for (int i = 0; i < Math.min(limit, 10); i++) {
            LogEntry log = new LogEntry();
            log.setTimestamp(FORMATTER.format(now.minus(i, ChronoUnit.MINUTES)));
            log.setLevel(i % 3 == 0 ? "ERROR" : (i % 3 == 1 ? "WARN" : "INFO"));
            log.setService("generic-service");
            log.setInstance("instance-" + i);
            log.setMessage("日志消息 #" + i + ", 查询条件: " + query);
            log.setMetrics(new HashMap<>());
            logs.add(log);
        }
        
        return logs;
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 日志条目
     */
    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("level")
        private String level;
        
        @JsonProperty("service")
        private String service;
        
        @JsonProperty("instance")
        private String instance;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("metrics")
        private Map<String, String> metrics;
    }
    
    /**
     * 日志查询输出
     */
    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("region")
        private String region;
        
        @JsonProperty("log_topic")
        private String logTopic;
        
        @JsonProperty("query")
        private String query;
        
        @JsonProperty("logs")
        private List<LogEntry> logs;
        
        @JsonProperty("total")
        private int total;
        
        @JsonProperty("message")
        private String message;
    }
    
    /**
     * 日志主题信息
     */
    @Data
    public static class LogTopicInfo {
        @JsonProperty("topic_name")
        private String topicName;

        @JsonProperty("topic_id")
        private String topicId;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("example_queries")
        private List<String> exampleQueries;
        
        @JsonProperty("related_alerts")
        private List<String> relatedAlerts;
    }
    
    /**
     * 日志主题列表输出
     */
    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("topics")
        private List<LogTopicInfo> topics;
        
        @JsonProperty("available_regions")
        private List<String> availableRegions;
        
        @JsonProperty("default_region")
        private String defaultRegion;
        
        @JsonProperty("message")
        private String message;
    }
}
