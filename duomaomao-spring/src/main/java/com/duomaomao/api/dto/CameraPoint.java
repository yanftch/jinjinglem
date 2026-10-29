package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 探头点位。注意 {@code position} 是 {@code [经度, 纬度]}（GCJ-02），不是 [lat, lng]。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CameraPoint(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("position") List<Double> position,
        @JsonProperty("aa") String aa,
        @JsonProperty("time") String time,
        @JsonProperty("edittime") String edittime,
        @JsonProperty("href") String href) {

    /** 经度；缺失时返回 null。 */
    public Double longitude() {
        return position == null || position.size() < 2 ? null : position.get(0);
    }

    /** 纬度；缺失时返回 null。 */
    public Double latitude() {
        return position == null || position.size() < 2 ? null : position.get(1);
    }
}
