package com.duomaomao.api.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.duomaomao.api.config.CloudApiProperties;
import com.duomaomao.api.dto.CameraComments;
import com.duomaomao.api.dto.CameraPoint;
import com.duomaomao.api.dto.ExportDocument;
import com.duomaomao.api.dto.ExportPreview;
import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.dto.LabelsResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportServiceTest {

    @TempDir
    Path tempDir;

    private ExportService service;

    private CloudApiProperties properties() {
        CloudApiProperties props = new CloudApiProperties();
        props.setExportFile(tempDir.resolve("cameras.json").toString());
        props.setExportConcurrency(2);
        props.setBearerToken("test");
        return props;
    }

    private ExportService newService(DuomaomaoService api) {
        service = new ExportService(api, properties(), new ObjectMapper());
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static CameraPoint point(int id, String name) {
        return new CameraPoint(id, name, List.of(116.1, 39.1), "2", "2020-01-01", "", "/content/?" + id + ".html");
    }

    private static ExportStatus await(ExportService service) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ExportStatus status = service.status();
            if (!"RUNNING".equals(status.state())) {
                return status;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("export did not finish in time: " + service.status());
    }

    @Test
    void dedupesPagesAndWritesFixedFile() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        // page1 三条；page2 只有一条新的 + 一条重复；page3 回落成 page1（必须靠「无新增」停下）
        when(api.labels(1, true)).thenReturn(new LabelsResult(
                List.of(point(3, "c3"), point(2, "c2"), point(1, "c1")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(
                List.of(point(0, "c0"), point(1, "c1")), null));
        when(api.labels(3, true)).thenReturn(new LabelsResult(
                List.of(point(3, "c3"), point(2, "c2"), point(1, "c1")), List.of()));

        when(api.comments(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            return new CameraComments("camera-" + id, "2020-01-0" + id, List.of(116.1, 39.1),
                    List.of(new CameraComments.Comment("u" + id, "2021/1/1", "text" + id),
                            new CameraComments.Comment("v" + id, "2021/1/2", "text2")));
        });

        ExportService svc = newService(api);
        svc.start(null, true);
        ExportStatus status = await(svc);

        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.total()).isEqualTo(4);
        assertThat(status.done()).isEqualTo(4);
        assertThat(status.failed()).isZero();
        assertThat(status.file()).isEqualTo(tempDir.resolve("cameras.json").toString());
        assertThat(status.sizeBytes()).isPositive();

        ExportDocument document = new ObjectMapper().readValue(
                tempDir.resolve("cameras.json").toFile(), ExportDocument.class);
        assertThat(document.total()).isEqualTo(4);
        assertThat(document.detailRequested()).isTrue();
        assertThat(document.cameras()).extracting(c -> c.id()).containsExactly(3, 2, 1, 0);
        assertThat(document.cameras().get(0).cameraName()).isEqualTo("camera-3");
        assertThat(document.cameras().get(0).commentCount()).isEqualTo(2);
        assertThat(document.cameras().get(0).detailError()).isNull();
    }

    @Test
    void dropsUpstreamPlaceholderCommentsForUnknownId() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(List.of(point(7, "c7")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));
        // 上游对不存在的 id 返回 cameraName="" + 全站最新 7 条，必须丢掉
        when(api.comments(7)).thenReturn(new CameraComments("", "", List.of(),
                List.of(new CameraComments.Comment("别人", "2021/1/1", "和我无关"))));

        ExportService svc = newService(api);
        svc.start(null, true);
        ExportStatus status = await(svc);

        assertThat(status.state()).isEqualTo("DONE");
        ExportDocument document = new ObjectMapper().readValue(
                tempDir.resolve("cameras.json").toFile(), ExportDocument.class);
        assertThat(document.cameras()).hasSize(1);
        assertThat(document.cameras().get(0).commentCount()).isZero();
        assertThat(document.cameras().get(0).comments()).isEmpty();
        assertThat(document.cameras().get(0).cameraName()).isNull();
    }

    @Test
    void oneFailingDetailDoesNotAbortTheWholeExport() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(
                List.of(point(2, "c2"), point(1, "c1")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));
        when(api.comments(2)).thenThrow(new UpstreamException("boom"));
        when(api.comments(1)).thenReturn(new CameraComments("c1", "2020-01-01", List.of(),
                List.of(new CameraComments.Comment("u", "t", "hello"))));

        ExportService svc = newService(api);
        svc.start(null, true);
        ExportStatus status = await(svc);

        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.failed()).isEqualTo(1);

        ExportDocument document = new ObjectMapper().readValue(
                tempDir.resolve("cameras.json").toFile(), ExportDocument.class);
        assertThat(document.detailFailures()).isEqualTo(1);
        assertThat(document.cameras()).hasSize(2);
        assertThat(document.cameras().get(0).detailError()).contains("boom");
        assertThat(document.cameras().get(0).name()).isEqualTo("c2");   // 基础字段仍在
        // 顺序必须和上游一致，不能因为并发而乱序
        assertThat(document.cameras()).extracting(c -> c.id()).containsExactly(2, 1);
    }

    @Test
    void retriesOnceWhenDetailFailsTransiently() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(List.of(point(6, "c6")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));
        when(api.comments(6))
                .thenThrow(new UpstreamException("blip"))
                .thenReturn(new CameraComments("c6", "2020", List.of(),
                        List.of(new CameraComments.Comment("u", "t", "hi"))));

        ExportService svc = newService(api);
        svc.start(null, true);
        ExportStatus status = await(svc);

        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.failed()).isZero();
        ExportDocument document = new ObjectMapper().readValue(
                tempDir.resolve("cameras.json").toFile(), ExportDocument.class);
        assertThat(document.cameras().get(0).commentCount()).isEqualTo(1);
        assertThat(document.cameras().get(0).detailError()).isNull();
        org.mockito.Mockito.verify(api, org.mockito.Mockito.times(2)).comments(6);
    }

    @Test
    void limitAndNoDetailModeSkipCommentRequests() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(
                List.of(point(9, "c9"), point(8, "c8"), point(7, "c7")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));

        ExportService svc = newService(api);
        svc.start(2, false);
        ExportStatus status = await(svc);

        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.total()).isEqualTo(2);
        ExportDocument document = new ObjectMapper().readValue(
                tempDir.resolve("cameras.json").toFile(), ExportDocument.class);
        assertThat(document.cameras()).extracting(c -> c.id()).containsExactly(9, 8);
        assertThat(document.detailRequested()).isFalse();
        assertThat(document.cameras().get(0).comments()).isEmpty();
        org.mockito.Mockito.verify(api, org.mockito.Mockito.never()).comments(anyInt());
    }

    @Test
    void rejectsSecondStartWhileRunning() {
        DuomaomaoService api = mock(DuomaomaoService.class);
        // 让 labels 卡住，保证任务还在 RUNNING
        when(api.labels(anyInt(), eq(true))).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(1500);
            return new LabelsResult(List.of(), List.of());
        });

        ExportService svc = newService(api);
        svc.start(null, false);

        assertThatThrownBy(() -> svc.start(null, false))
                .isInstanceOf(ExportInProgressException.class);
    }

    @Test
    void previewPaginatesSearchesAndHidesCommentBodies() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(
                List.of(point(3, "西单路口"), point(2, "国贸桥"), point(1, "西直门")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));
        when(api.comments(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            return new CameraComments("cam-" + id, "2020", List.of(),
                    List.of(new CameraComments.Comment("u", "t", "content")));
        });

        ExportService svc = newService(api);
        svc.start(null, true);
        await(svc);

        ExportPreview all = svc.preview(1, 2, null);
        assertThat(all.matched()).isEqualTo(3);
        assertThat(all.items()).hasSize(2);
        assertThat(all.items().get(0).comments()).isNull();       // 列表不带正文
        assertThat(all.items().get(0).commentCount()).isEqualTo(1);

        ExportPreview page2 = svc.preview(2, 2, null);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).id()).isEqualTo(1);

        ExportPreview searched = svc.preview(1, 10, "西");
        assertThat(searched.matched()).isEqualTo(2);
        assertThat(searched.items()).extracting(c -> c.name()).containsExactly("西单路口", "西直门");

        assertThat(svc.camera(2)).get().extracting(c -> c.cameraName()).isEqualTo("cam-2");
        assertThat(svc.camera(2)).get().satisfies(c -> assertThat(c.comments()).hasSize(1));
        assertThat(svc.camera(999)).isEmpty();
    }

    @Test
    void readsExistingFileAfterRestart() throws Exception {
        DuomaomaoService api = mock(DuomaomaoService.class);
        when(api.labels(1, true)).thenReturn(new LabelsResult(List.of(point(5, "c5")), List.of()));
        when(api.labels(2, true)).thenReturn(new LabelsResult(List.of(), List.of()));
        when(api.comments(5)).thenReturn(new CameraComments("c5", "2020", List.of(), List.of()));

        ExportService local = newService(api);
        local.start(null, true);
        await(local);
        local.shutdown();

        // 新实例：lastDocument 为空，应从磁盘懒加载
        ExportService restarted = new ExportService(api, properties(), new ObjectMapper());
        try {
            assertThat(restarted.status().state()).isEqualTo("IDLE");
            assertThat(restarted.status().sizeBytes()).isPositive();
            assertThat(restarted.preview(1, 10, null).matched()).isEqualTo(1);
            assertThat(restarted.preview(1, 10, null).items().get(0).name()).isEqualTo("c5");
        } finally {
            restarted.shutdown();
        }
        assertThat(Files.exists(tempDir.resolve("cameras.json"))).isTrue();
    }
}
