package com.duomaomao.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** /getgaodeSDK4Android202609 的回参。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GaodeKeys(
        @JsonProperty("success") Boolean success,
        @JsonProperty("keys") List<String> keys,
        @JsonProperty("count") Integer count,
        @JsonProperty("webServiceKeys") List<String> webServiceKeys,
        @JsonProperty("webServiceCount") Integer webServiceCount,
        @JsonProperty("timestamp") Long timestamp) {
}
