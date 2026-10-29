package com.duomaomao.api.service;

/** 上游调用失败（网络/解密/反序列化），统一转 502。 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
