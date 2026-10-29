package com.duomaomao.api.service;

/** 上游网关返回非 2xx，保留状态码与明文错误体，供 API 层转 502。 */
public class UpstreamStatusException extends RuntimeException {

    private final int statusCode;
    private final byte[] responseBody;

    public UpstreamStatusException(int statusCode, byte[] responseBody) {
        super("upstream HTTP " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? new byte[0] : responseBody.clone();
    }

    public int statusCode() {
        return statusCode;
    }

    public byte[] responseBody() {
        return responseBody.clone();
    }
}
