package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条导出的探头记录 = {@code labels} 里的基础字段 + {@code comment(dowhat=query)} 的详情。
 *
 * @param position    {@code [经度, 纬度]}（GCJ-02），与上游一致
 * @param cameraName  详情接口回传的规范化名称；上游对不存在的 id 返回空串
 * @param commentCount 留言条数（列表页只返回这个，不带正文）
 * @param comments    留言正文；列表页视图里为 null
 * @param detailError 非空表示这个探头的详情抓取失败，基础字段仍然保留，下次可重试
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CameraDetail(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("position") List<Double> position,
        @JsonProperty("aa") String aa,
        @JsonProperty("time") String time,
        @JsonProperty("edittime") String edittime,
        @JsonProperty("href") String href,
        @JsonProperty("cameraName") String cameraName,
        @JsonProperty("creatTime") String creatTime,
        @JsonProperty("commentCount") int commentCount,
        @JsonProperty("comments") List<CameraComments.Comment> comments,
        @JsonProperty("detailError") String detailError) {

    /** 列表页用的轻量视图：丢掉评论正文，只留条数。 */
    public CameraDetail summary() {
        return new CameraDetail(id, name, position, aa, time, edittime, href,
                cameraName, creatTime, commentCount, null, detailError);
    }

    public static CameraDetail base(CameraPoint point) {
        return new CameraDetail(point.id(), point.name(), point.position(), point.aa(), point.time(),
                point.edittime(), point.href(), null, null, 0, List.of(), null);
    }
}
