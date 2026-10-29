package com.example.cloudapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Spring Boot adapter. Requires the normal Jackson dependency supplied by Spring Boot. */
public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper objectMapper;

    public JacksonJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String write(Object value) throws CloudApiException {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CloudApiException("Unable to serialize request JSON", e);
        }
    }

    @Override
    public <T> T read(String json, Class<T> targetType) throws CloudApiException {
        try {
            return objectMapper.readValue(json, targetType);
        } catch (JsonProcessingException e) {
            throw new CloudApiException("Unable to parse decrypted response JSON", e);
        }
    }
}
