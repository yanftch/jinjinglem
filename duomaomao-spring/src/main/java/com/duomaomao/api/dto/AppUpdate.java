package com.duomaomao.api.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** /androidCheckUpdate202609 的回参：版本信息 + 动态端点路径。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppUpdate(
        @JsonProperty("version") String version,
        @JsonProperty("versionCode") Integer versionCode,
        @JsonProperty("downloadUrl") String downloadUrl,
        @JsonProperty("forceUpdate") Boolean forceUpdate,
        @JsonProperty("updateLog") String updateLog,
        @JsonProperty("paths") Map<String, String> paths) {
}
