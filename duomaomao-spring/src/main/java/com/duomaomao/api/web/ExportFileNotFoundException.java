package com.duomaomao.api.web;

import java.nio.file.Path;

/** 还没有导出文件（或文件被删了）。 */
public class ExportFileNotFoundException extends RuntimeException {

    public ExportFileNotFoundException(Path path) {
        super("还没有导出文件：" + path + "。先点「开始抓取」或调 POST /api/v1/export/start");
    }
}
