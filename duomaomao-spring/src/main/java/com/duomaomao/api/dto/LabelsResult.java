package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /qwvsxdvglkj 的回参。
 *
 * @param labelsData  全量探头（page=1 为 5000 条，page=2 为剩余记录）
 * @param showPoints  服务端额外返回的「最新 10 条」，字段与 labelsData 相同
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelsResult(
        @JsonProperty("LabelsData") List<CameraPoint> labelsData,
        @JsonProperty("showPoints") List<CameraPoint> showPoints) {
}
