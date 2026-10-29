package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 导出结果的分页视图（给前端表格用，不含评论正文）。
 *
 * @param matched 过滤后的总条数
 * @param items   当前页，元素里的 {@code comments} 为 null，只有 {@code commentCount}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportPreview(
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("matched") int matched,
        @JsonProperty("items") List<CameraDetail> items,
        @JsonProperty("generatedAt") String generatedAt,
        @JsonProperty("file") String file) {
}
