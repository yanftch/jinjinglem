package cn.jinjing.app;

import com.duomaomao.api.dto.ExportStatus;
import com.duomaomao.api.service.ExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncRunnerTest {
    @TempDir Path temp;

    @SuppressWarnings("unchecked")
    @Test
    void archivesSuccessfulExportAndReplacesSameDayFile() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ImportService imports = mock(ImportService.class);
        ReviewService reviews = mock(ReviewService.class);
        ReviewMailService mail = mock(ReviewMailService.class);
        ObjectProvider<ExportService> provider = mock(ObjectProvider.class);
        ExportService export = mock(ExportService.class);
        when(provider.getObject()).thenReturn(export);
        when(export.status()).thenReturn(new ExportStatus("DONE", 1, 1, 0, 100,
                null, null, 1L, null, null, "完成", null));
        when(imports.submitPath(any(Path.class))).thenReturn(10L);
        Path source = temp.resolve("current.json");
        when(export.exportFilePath()).thenReturn(source);
        SyncRunner runner = new SyncRunner(db, imports, reviews, mail, provider, temp.resolve("snapshots").toString());

        Files.writeString(source, "first");
        runner.execute(1L);
        Path archive = temp.resolve("snapshots/duomaomao_"
                + LocalDate.now(SyncService.ZONE).format(DateTimeFormatter.BASIC_ISO_DATE) + ".json");
        assertThat(Files.readString(archive)).isEqualTo("first");
        verify(reviews).prepare(1L, 10L);
        verify(mail).send(1L);
        verify(imports, never()).processBlocking(anyLong(), any());

        Files.writeString(source, "second");
        runner.execute(2L);
        assertThat(Files.readString(archive)).isEqualTo("second");
        verify(reviews).prepare(2L, 10L);
        verify(mail).send(2L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedExportNeverArchivesOrImports() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ImportService imports = mock(ImportService.class);
        ReviewService reviews = mock(ReviewService.class);
        ReviewMailService mail = mock(ReviewMailService.class);
        ObjectProvider<ExportService> provider = mock(ObjectProvider.class);
        ExportService export = mock(ExportService.class);
        when(provider.getObject()).thenReturn(export);
        when(export.status()).thenReturn(new ExportStatus("FAILED", 0, 0, 0, 0,
                null, null, 1L, null, null, "失败", "上游不可用"));
        SyncRunner runner = new SyncRunner(db, imports, reviews, mail, provider, temp.resolve("snapshots").toString());
        runner.execute(3L);
        assertThat(Files.exists(temp.resolve("snapshots"))).isFalse();
        verify(imports, never()).submitPath(any(Path.class));
        verify(mail).send(3L);
    }
}
