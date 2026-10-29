package com.duomaomao.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出任务状态，前端轮询这个接口画进度条。
 *
 * @param state     {@code IDLE} / {@code RUNNING} / {@code DONE} / {@code FAILED}
 * @param done      已处理探头数
 * @param total     总数；任务刚启动、还没拉到 labels 时为 0（此时用 percent 判空）
 * @param failed    详情抓取失败的探头数
 * @param percent   {@code done/total*100}，total=0 时为 0
 * @param file      落盘的绝对路径
 * @param sizeBytes 文件大小
 * @param message   给前端看的说明（例如「已完成」「正在抓取详情」）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportStatus(
        @JsonProperty("state") String state,
        @JsonProperty("done") int done,
        @JsonProperty("total") int total,
        @JsonProperty("failed") int failed,
        @JsonProperty("percent") int percent,
        @JsonProperty("startedAt") String startedAt,
        @JsonProperty("finishedAt") String finishedAt,
        @JsonProperty("durationMs") Long durationMs,
        @JsonProperty("file") String file,
        @JsonProperty("sizeBytes") Long sizeBytes,
        @JsonProperty("message") String message,
        @JsonProperty("error") String error) {
}
