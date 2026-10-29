package com.duomaomao.api.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.duomaomao.api.config.CloudApiProperties;
import com.duomaomao.api.dto.CameraComments;
import com.duomaomao.api.dto.CameraDetail;
import com.duomaomao.api.dto.CameraPoint;
import com.duomaomao.api.dto.ExportDocument;
import com.duomaomao.api.dto.ExportPreview;
import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.dto.LabelsResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 全量导出：拉两页 {@code labels} 汇总所有探头，再逐个查 {@code comment} 详情，
 * 最后写成一个固定文件（默认 {@code ./duomaomao-cameras.json}）。
 *
 * <p>这个任务会发出约 7300 次上游请求，所以：</p>
 * <ul>
 *   <li>同一时间只允许一个任务（{@link ExportInProgressException}）；</li>
 *   <li>详情抓取有并发上限（{@code duomaomao.export-concurrency}，默认 6）；</li>
 *   <li>单个探头失败不影响整体，错误记在 {@code detailError} 里；</li>
 *   <li>落盘用「临时文件 + 原子重命名」，前端永远读不到半截 JSON。</li>
 * </ul>
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** 详情失败后的重试间隔。 */
    private static final Duration DETAIL_RETRY_BACKOFF = Duration.ofMillis(250);

    public enum State {
        IDLE, RUNNING, DONE, FAILED
    }

    private final DuomaomaoService api;
    private final CloudApiProperties properties;
    private final ObjectMapper objectMapper;
    private final Path exportFile;

    private final ExecutorService jobRunner = Executors.newSingleThreadExecutor(
            runnable -> daemon(runnable, "export-job"));
    private final ExecutorService workers;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile int total;
    private volatile String startedAt;
    private volatile String finishedAt;
    private volatile Long durationMs;
    private volatile String message = "尚未导出";
    private volatile String error;

    /** 导出完成后常驻内存，供前端分页/搜索；进程重启后从磁盘懒加载。 */
    private volatile ExportDocument lastDocument;

    public ExportService(DuomaomaoService api, CloudApiProperties properties, ObjectMapper objectMapper) {
        this.api = api;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.exportFile = Path.of(properties.getExportFile()).toAbsolutePath().normalize();
        int concurrency = Math.max(1, properties.getExportConcurrency());
        this.workers = Executors.newFixedThreadPool(concurrency,
                runnable -> daemon(runnable, "export-worker"));
    }

    // ---------------------------------------------------------------- job control

    /**
     * 启动一次导出。立即返回当前状态（RUNNING），实际抓取在后台线程池里跑。
     *
     * @param limit    只导前 N 条，用于快速试跑；null / &lt;=0 表示全量
     * @param withDetail 是否抓 comment 详情；false 时只导 labels 的字段（几秒钟完成）
     */
    public ExportStatus start(Integer limit, boolean withDetail) {
        if (!running.compareAndSet(false, true)) {
            throw new ExportInProgressException();
        }
        state.set(State.RUNNING);
        done.set(0);
        failed.set(0);
        total = 0;
        error = null;
        durationMs = null;
        finishedAt = null;
        startedAt = nowIso();
        message = withDetail ? "正在拉取探头列表" : "正在拉取探头列表（不抓详情）";
        int effectiveLimit = limit == null || limit <= 0 ? 0 : limit;

        try {
            jobRunner.submit(() -> runJob(effectiveLimit, withDetail));
        } catch (RuntimeException e) {
            running.set(false);
            state.set(State.FAILED);
            error = "无法提交导出任务: " + e.getMessage();
            message = "导出失败";
            throw e;
        }
        return status();
    }

    private void runJob(int limit, boolean withDetail) {
        long startNanos = System.nanoTime();
        try {
            List<CameraPoint> cameras = collectCameras();
            if (limit > 0 && cameras.size() > limit) {
                cameras = cameras.subList(0, limit);
            }
            total = cameras.size();
            message = withDetail ? "正在抓取留言详情" : "正在整理数据";

            List<CameraDetail> details = withDetail
                    ? fetchDetails(cameras)
                    : cameras.stream().map(CameraDetail::base).toList();
            if (!withDetail) {
                done.set(cameras.size());
            }

            ExportDocument document = new ExportDocument(
                    nowIso(),
                    details.size(),
                    withDetail,
                    failed.get(),
                    (System.nanoTime() - startNanos) / 1_000_000,
                    details);

            writeAtomically(document);
            lastDocument = document;
            durationMs = document.durationMs();
            finishedAt = nowIso();
            state.set(State.DONE);
            message = "导出完成：" + details.size() + " 条探头"
                    + (withDetail ? "，详情失败 " + failed.get() + " 条" : "");

            log.info("export done: {} cameras, {} detail failures, {} ms -> {}",
                    details.size(), failed.get(), document.durationMs(), exportFile);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            message = "导出失败";
            durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            finishedAt = nowIso();
            state.set(State.FAILED);
            log.warn("export failed", e);
        } finally {
            running.set(false);
        }
    }

    /** 上游 page>=3 会回落成 page=1，所以用「没有新增 id」来判断结束，而不是死写 2 页。 */
    private List<CameraPoint> collectCameras() {
        Map<Integer, CameraPoint> unique = new LinkedHashMap<>();
        for (int page = 1; page <= 4; page++) {
            LabelsResult result = api.labels(page, true);
            if (result == null || result.labelsData() == null || result.labelsData().isEmpty()) {
                break;
            }
            int before = unique.size();
            for (CameraPoint point : result.labelsData()) {
                unique.putIfAbsent(point.id(), point);
            }
            log.info("export: labels page {} -> {} rows, {} new (total {})",
                    page, result.labelsData().size(), unique.size() - before, unique.size());
            if (unique.size() == before) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** 并发抓详情，但结果顺序与输入一致（主播按 id 从大到小）。 */
    private List<CameraDetail> fetchDetails(List<CameraPoint> cameras) throws Exception {
        List<Future<CameraDetail>> futures = new ArrayList<>(cameras.size());
        for (CameraPoint point : cameras) {
            futures.add(workers.submit(() -> fetchDetail(point)));
        }
        List<CameraDetail> details = new ArrayList<>(cameras.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                details.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                failed.incrementAndGet();
                details.add(baseWithError(cameras.get(i), e));
            }
        }
        return details;
    }

    private CameraDetail fetchDetail(CameraPoint point) {
        try {
            CameraComments comments = fetchCommentsWithRetry(point.id());
            // 上游对不存在的 id 不报错，而是返回「全站最新 7 条」且 cameraName 为空。
            // 这种数据必须丢掉，否则导出文件里会混进别人的留言。
            boolean known = comments != null
                    && comments.cameraName() != null
                    && !comments.cameraName().isBlank();
            List<CameraComments.Comment> list = known && comments.comments() != null
                    ? comments.comments()
                    : List.of();
            return new CameraDetail(
                    point.id(), point.name(), point.position(), point.aa(), point.time(),
                    point.edittime(), point.href(),
                    known ? comments.cameraName() : null,
                    known ? comments.creatTime() : null,
                    list.size(),
                    list,
                    null);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("detail failed for camera {}: {}", point.id(), reason);
            return baseWithError(point, e);
        } finally {
            done.incrementAndGet();
        }
    }

    /**
     * 单条详情重试一次。
     *
     * <p>7300 次请求里总会有几条撞上 CDN 抖动或上游函数偶发异常（实测约 0.8%）。
     * 重试一次能把失败率压到很低，代价只是极少数请求多跑一次。</p>
     */
    private CameraComments fetchCommentsWithRetry(int id) {
        try {
            return api.comments(id);
        } catch (RuntimeException first) {
            try {
                Thread.sleep(DETAIL_RETRY_BACKOFF.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return api.comments(id);
        }
    }

    private CameraDetail baseWithError(CameraPoint point, Exception e) {
        CameraDetail base = CameraDetail.base(point);
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new CameraDetail(base.id(), base.name(), base.position(), base.aa(), base.time(),
                base.edittime(), base.href(), null, null, 0, List.of(), reason);
    }

    private void writeAtomically(ExportDocument document) throws IOException {
        Path parent = exportFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent == null ? Path.of(".") : parent, "cameras-", ".json.tmp");
        try {
            objectMapper.writeValue(tmp.toFile(), document);
            try {
                Files.move(tmp, exportFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, exportFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---------------------------------------------------------------- read side

    public ExportStatus status() {
        long size = -1;
        try {
            if (Files.isReadable(exportFile)) {
                size = Files.size(exportFile);
            }
        } catch (IOException ignored) {
            // 文件刚被替换掉，忽略
        }
        int t = total;
        int d = done.get();
        int percent = t <= 0 ? 0 : (int) Math.min(100, Math.round(d * 100.0 / t));
        return new ExportStatus(
                state.get().name(),
                d,
                t,
                failed.get(),
                percent,
                startedAt,
                finishedAt,
                durationMs,
                exportFile.toString(),
                size < 0 ? null : size,
                message,
                error);
    }

    /** 分页 + 关键字过滤，只返回不带评论正文的摘要。 */
    public ExportPreview preview(int page, int size, String query) {
        ExportDocument document = currentDocument()
                .orElseThrow(() -> new IllegalStateException("还没有导出文件，先点「开始抓取」"));
        List<CameraDetail> all = document.cameras() == null ? List.of() : document.cameras();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<CameraDetail> matched = q.isEmpty()
                ? all
                : all.stream().filter(camera -> matches(camera, q)).toList();

        int safeSize = Math.max(1, Math.min(size, 500));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        List<CameraDetail> items = matched.subList(from, to).stream()
                .map(CameraDetail::summary)
                .toList();
        return new ExportPreview(safePage, safeSize, matched.size(), items,
                document.generatedAt(), exportFile.toString());
    }

    public Optional<CameraDetail> camera(int id) {
        return currentDocument()
                .flatMap(document -> document.cameras() == null ? Optional.<CameraDetail>empty()
                        : document.cameras().stream().filter(camera -> camera.id() == id).findFirst());
    }

    /** 已导出的文件；不存在时返回 empty，由 controller 转 404。 */
    public Optional<Path> exportFile() {
        return Files.isReadable(exportFile) ? Optional.of(exportFile) : Optional.empty();
    }

    public Path exportFilePath() {
        return exportFile;
    }

    private Optional<ExportDocument> currentDocument() {
        ExportDocument cached = lastDocument;
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!Files.isReadable(exportFile)) {
            return Optional.empty();
        }
        synchronized (this) {
            if (lastDocument != null) {
                return Optional.of(lastDocument);
            }
            try {
                ExportDocument loaded = objectMapper.readValue(exportFile.toFile(), ExportDocument.class);
                lastDocument = loaded;
                return Optional.of(loaded);
            } catch (IOException e) {
                log.warn("cannot read existing export file {}: {}", exportFile, e.getMessage());
                return Optional.empty();
            }
        }
    }

    private boolean matches(CameraDetail camera, String q) {
        return String.valueOf(camera.id()).contains(q)
                || contains(camera.name(), q)
                || contains(camera.cameraName(), q)
                || contains(camera.href(), q);
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    // ---------------------------------------------------------------- misc

    private String nowIso() {
        return OffsetDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        jobRunner.shutdownNow();
        try {
            workers.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            jobRunner.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
