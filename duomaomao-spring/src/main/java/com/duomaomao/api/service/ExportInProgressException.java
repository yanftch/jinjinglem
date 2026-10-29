package com.duomaomao.api.service;

/** 已有导出任务在跑，同一时间只允许一个（上游会被打成 DDOS）。 */
public class ExportInProgressException extends RuntimeException {

    public ExportInProgressException() {
        super("已有导出任务在运行，请等它结束或轮询 /api/v1/export/status");
    }
}
