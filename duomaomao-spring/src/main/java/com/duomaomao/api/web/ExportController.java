package com.duomaomao.api.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.duomaomao.api.dto.CameraDetail;
import com.duomaomao.api.dto.ExportPreview;
import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.service.ExportService;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全量导出：前端页面（{@code /}）点按钮 → 这里起后台任务 → 轮询 status → 读 preview。
 */
@RestController
@RequestMapping(path = "/api/v1/export", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 启动导出（异步，立刻返回）。
     *
     * @param limit  只导前 N 条，方便先试跑一次；不传 = 全量
     * @param detail 是否抓留言详情，默认 true；false 时几秒就能出文件
     */
    @PostMapping("/start")
    public ResponseEntity<ExportStatus> start(
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "true") boolean detail) {
        ExportStatus status = exportService.start(limit, detail);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    /** 进度条数据源；前端在 RUNNING 时每秒轮询。 */
    @GetMapping("/status")
    public ExportStatus status() {
        return exportService.status();
    }

    /** 分页 + 关键字（id / 名称 / href）过滤，只返回摘要。 */
    @GetMapping("/preview")
    public ExportPreview preview(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "50") int size,
                                 @RequestParam(required = false) String q) {
        return exportService.preview(page, size, q);
    }

    /** 单条完整详情（含全部留言）。 */
    @GetMapping("/cameras/{id}")
    public CameraDetail camera(@PathVariable int id) {
        return exportService.camera(id).orElseThrow(() -> new CameraNotFoundException(id));
    }

    /** 把落盘文件作为附件下载。 */
    @GetMapping("/file")
    public ResponseEntity<Resource> download() throws IOException {
        Path path = exportService.exportFile()
                .orElseThrow(() -> new ExportFileNotFoundException(exportService.exportFilePath()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }
}
