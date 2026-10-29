package com.duomaomao.api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 给上游响应准备一个「宽容模式」的 ObjectMapper。
 *
 * <p>高德透传的 {@code navigationSteps[].assistant_action}、{@code toll_road}
 * 在同一次响应里既可能是数组也可能直接是空字符串（实测 assistant_action 就是如此），
 * 严格模式会直接抛 {@code MismatchedInputException}。这里只对上游解码放宽，
 * 不污染本服务自己的 HTTP 序列化。</p>
 */
public final class UpstreamJson {

    private UpstreamJson() {
    }

    public static ObjectMapper lenient(ObjectMapper base) {
        return base.copy()
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, false);
    }
}
