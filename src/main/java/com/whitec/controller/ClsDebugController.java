package com.whitec.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whitec.agent.tool.QueryLogsTools;
import com.whitec.config.ClsProperties;
import lombok.Data;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地联调用的 CLS 调试接口。
 * 仅在 local-monitoring profile 下启用，避免影响常规环境。
 */
//@Profile("local-monitoring")
@RestController
@RequestMapping("/api/debug/cls")
public class ClsDebugController {

    private static final Set<String> CLS_MCP_TOOL_NAMES = new LinkedHashSet<>(List.of(
            "GetTopicInfoByName",
            "GetRegionCodeByName",
            "TextToSearchLogQuery",
            "ConvertTimeStringToTimestamp",
            "SearchLog"
    ));

    private final QueryLogsTools queryLogsTools;
    private final ToolCallbackProvider toolCallbackProvider;
    private final ClsProperties clsProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClsDebugController(QueryLogsTools queryLogsTools,
                              ToolCallbackProvider toolCallbackProvider,
                              ClsProperties clsProperties) {
        this.queryLogsTools = queryLogsTools;
        this.toolCallbackProvider = toolCallbackProvider;
        this.clsProperties = clsProperties;
    }

    @GetMapping("/mode")
    public DebugModeResponse getMode() {
        DebugModeResponse response = new DebugModeResponse();
        response.setMockEnabled(clsProperties.isMockEnabled());
        response.setMode(clsProperties.isMockEnabled() ? "mock" : "mcp");
        response.setRegion(clsProperties.getRegion());
        response.setConfiguredTopics(clsProperties.getTopics());
        response.setAvailableMcpTools(getClsMcpTools().stream()
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toList()));
        return response;
    }

    @GetMapping("/topics")
    public String getAvailableLogTopics() {
        return queryLogsTools.getAvailableLogTopics();
    }

    @GetMapping("/tools")
    public List<McpToolInfo> getClsMcpToolSchemas() {
        return getClsMcpTools().stream().map(tool -> {
            McpToolInfo info = new McpToolInfo();
            info.setName(tool.getToolDefinition().name());
            info.setDescription(tool.getToolDefinition().description());
            info.setInputSchema(tool.getToolDefinition().inputSchema());
            return info;
        }).collect(Collectors.toList());
    }

    @PostMapping("/call")
    public String callTool(@RequestBody McpToolCallRequest request) throws Exception {
        if (request == null || request.getToolName() == null || request.getToolName().isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }

        ToolCallback tool = getClsMcpTools().stream()
                .filter(item -> item.getToolDefinition().name().equals(request.getToolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到 CLS MCP 工具: " + request.getToolName()));

        String argumentsJson = request.getArguments() == null ? "{}" : objectMapper.writeValueAsString(request.getArguments());
        return tool.call(argumentsJson);
    }

    private List<ToolCallback> getClsMcpTools() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(tool -> CLS_MCP_TOOL_NAMES.contains(tool.getToolDefinition().name()))
                .collect(Collectors.toList());
    }

    @Data
    public static class McpToolCallRequest {
        @JsonProperty("tool_name")
        private String toolName;

        @JsonProperty("arguments")
        private JsonNode arguments;
    }

    @Data
    public static class McpToolInfo {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("input_schema")
        private String inputSchema;
    }

    @Data
    public static class DebugModeResponse {
        @JsonProperty("mode")
        private String mode;

        @JsonProperty("mock_enabled")
        private boolean mockEnabled;

        @JsonProperty("region")
        private String region;

        @JsonProperty("configured_topics")
        private Object configuredTopics;

        @JsonProperty("available_mcp_tools")
        private List<String> availableMcpTools;
    }
}
