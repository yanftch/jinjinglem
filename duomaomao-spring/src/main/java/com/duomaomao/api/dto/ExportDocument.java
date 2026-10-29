package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 全量导出的落盘文档，一个文件装完「所有探头 + 各自的留言详情」。
 *
 * @param generatedAt    导出完成时间（ISO-8601，本地时区）
 * @param total          探头总数（已按 id 去重）
 * @param detailRequested 是否抓了详情
 * @param detailFailures 详情抓取失败的探头数
 * @param durationMs     整个导出耗时
 * @param cameras        探头列表，严格保持上游返回顺序（大体 id 降序，上游本身不严格有序）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportDocument(
        @JsonProperty("generatedAt") String generatedAt,
        @JsonProperty("total") int total,
        @JsonProperty("detailRequested") boolean detailRequested,
        @JsonProperty("detailFailures") int detailFailures,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("cameras") List<CameraDetail> cameras) {
}
