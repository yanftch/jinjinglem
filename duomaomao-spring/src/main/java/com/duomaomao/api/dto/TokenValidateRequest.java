package com.duomaomao.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/tokens/validate 的请求体。 */
public record TokenValidateRequest(
        @NotBlank @Size(max = 2048) String token,
        @Size(max = 128) String deviceId) {

    public String deviceIdOrDefault() {
        return deviceId == null || deviceId.isBlank() ? "spring-api" : deviceId;
    }
}
