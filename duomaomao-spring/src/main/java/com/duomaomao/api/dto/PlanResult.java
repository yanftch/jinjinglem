package com.duomaomao.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /getPlanResultios202609 的回参。
 *
 * <p>该接口按 OPENID 返回「该用户最后一次规划结果」。没有结果时只有
 * {@code {"isSuccess":false}}，没有 {@code result} 字段。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanResult(
        @JsonProperty("isSuccess") boolean isSuccess,
        @JsonProperty("result") RoutePlanResult result) {
}
