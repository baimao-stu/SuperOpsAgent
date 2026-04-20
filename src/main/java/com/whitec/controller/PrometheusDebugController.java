package com.whitec.controller;

import com.whitec.agent.tool.QueryMetricsTools;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地联调用的 Prometheus 调试接口。
 * 仅在 local-monitoring profile 下启用，避免影响常规环境。
 */
@Profile("local-monitoring")
@RestController
@RequestMapping("/api/debug")
public class PrometheusDebugController {

    private final QueryMetricsTools queryMetricsTools;

    public PrometheusDebugController(QueryMetricsTools queryMetricsTools) {
        this.queryMetricsTools = queryMetricsTools;
    }

    @GetMapping("/prometheus-alerts")
    public String queryPrometheusAlerts() {
        return queryMetricsTools.queryPrometheusAlerts();
    }
}
