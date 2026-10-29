package com.duomaomao.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** /validateToken202609 的回参。注意 HTTP 200 + success=true 也可能 valid=false。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenValidation(
        @JsonProperty("success") Boolean success,
        @JsonProperty("valid") boolean valid,
        @JsonProperty("error") String error) {
}
