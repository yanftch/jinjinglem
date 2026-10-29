package com.duomaomao.api.service;

/** 并发规划请求超过本服务自设的上限，转为 429。 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
