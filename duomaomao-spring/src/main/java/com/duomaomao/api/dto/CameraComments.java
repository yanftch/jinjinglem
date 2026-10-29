package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /getCommentNew202609（dowhat=query）的回参。
 *
 * <p>坑：{@code id} 不存在时服务端不报错，而是返回全站最新 7 条留言，
 * 此时 {@code cameraName=""}、{@code position=[]}。调用方应先判断 id 是否合法，
 * 不能只看 comments 非空。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CameraComments(
        @JsonProperty("cameraName") String cameraName,
        @JsonProperty("creatTime") String creatTime,
        @JsonProperty("position") List<Double> position,
        @JsonProperty("comments") List<Comment> comments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comment(
            @JsonProperty("nickname") String nickname,
            @JsonProperty("time") String time,
            @JsonProperty("content") String content) {
    }
}
