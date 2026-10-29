package com.duomaomao.api.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调参数集中在 {@code duomaomao.*} 下，见 application.yml。
 */
@ConfigurationProperties(prefix = "duomaomao")
public class CloudApiProperties {

    private String baseUrl = "https://jinjing-0gxvttiv313dd8aa-1319848734.ap-shanghai.app.tcloudbase.com";
    private String bearerToken = "";
    private String bearerTokenFile = "";
    private String publicKeyBase64 = "";

    private Duration connectTimeout = Duration.ofSeconds(20);
    private Duration requestTimeout = Duration.ofSeconds(180);

    private Duration labelsCacheTtl = Duration.ofMinutes(10);
    private Duration commentsCacheTtl = Duration.ofMinutes(2);
    private Duration gaodeCacheTtl = Duration.ofHours(1);
    private Duration updateCacheTtl = Duration.ofMinutes(10);
    private Duration routeCacheTtl = Duration.ofMinutes(30);

    private int maxConcurrentRoutePlans = 3;
    private Duration routeQueueTimeout = Duration.ofSeconds(5);

    /** 全量导出落盘位置。相对路径按进程工作目录解析（IDEA 里通常是模块根目录）。 */
    private String exportFile = "duomaomao-cameras.json";
    /** 抓取评论详情时的并发数。上游对单 IP 无明显限流，但别开太大。 */
    private int exportConcurrency = 6;

    /** 是否沿用系统代理（macOS 的 JVM 会自动带入 http.proxyHost 等）。默认直连。 */
    private boolean useSystemProxy = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getBearerTokenFile() {
        return bearerTokenFile;
    }

    public void setBearerTokenFile(String bearerTokenFile) {
        this.bearerTokenFile = bearerTokenFile;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public void setPublicKeyBase64(String publicKeyBase64) {
        this.publicKeyBase64 = publicKeyBase64;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getLabelsCacheTtl() {
        return labelsCacheTtl;
    }

    public void setLabelsCacheTtl(Duration labelsCacheTtl) {
        this.labelsCacheTtl = labelsCacheTtl;
    }

    public Duration getCommentsCacheTtl() {
        return commentsCacheTtl;
    }

    public void setCommentsCacheTtl(Duration commentsCacheTtl) {
        this.commentsCacheTtl = commentsCacheTtl;
    }

    public Duration getGaodeCacheTtl() {
        return gaodeCacheTtl;
    }

    public void setGaodeCacheTtl(Duration gaodeCacheTtl) {
        this.gaodeCacheTtl = gaodeCacheTtl;
    }

    public Duration getUpdateCacheTtl() {
        return updateCacheTtl;
    }

    public void setUpdateCacheTtl(Duration updateCacheTtl) {
        this.updateCacheTtl = updateCacheTtl;
    }

    public Duration getRouteCacheTtl() {
        return routeCacheTtl;
    }

    public void setRouteCacheTtl(Duration routeCacheTtl) {
        this.routeCacheTtl = routeCacheTtl;
    }

    public int getMaxConcurrentRoutePlans() {
        return maxConcurrentRoutePlans;
    }

    public void setMaxConcurrentRoutePlans(int maxConcurrentRoutePlans) {
        this.maxConcurrentRoutePlans = maxConcurrentRoutePlans;
    }

    public Duration getRouteQueueTimeout() {
        return routeQueueTimeout;
    }

    public void setRouteQueueTimeout(Duration routeQueueTimeout) {
        this.routeQueueTimeout = routeQueueTimeout;
    }

    public String getExportFile() {
        return exportFile;
    }

    public void setExportFile(String exportFile) {
        this.exportFile = exportFile;
    }

    public int getExportConcurrency() {
        return exportConcurrency;
    }

    public void setExportConcurrency(int exportConcurrency) {
        this.exportConcurrency = exportConcurrency;
    }

    public boolean isUseSystemProxy() {
        return useSystemProxy;
    }

    public void setUseSystemProxy(boolean useSystemProxy) {
        this.useSystemProxy = useSystemProxy;
    }
}
