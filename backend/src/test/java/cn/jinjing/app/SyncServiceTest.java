package cn.jinjing.app;

import com.duomaomao.api.config.CloudApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceTest {
    @Test
    void manualRunWorksEvenWhenAutomaticScheduleIsDisabled() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        SyncRunner runner = mock(SyncRunner.class);
        ReviewMailService mail = mock(ReviewMailService.class);
        CloudApiProperties cloud = new CloudApiProperties();
        cloud.setBearerToken("test-token");
        when(db.queryForObject(anyString(), eq(Long.class), eq("MANUAL"))).thenReturn(42L);
        SyncService sync = new SyncService(db, runner, mail, cloud, false, "0 0 3 * * *", "");
        assertThat(sync.start("MANUAL")).isEqualTo(42L);
        verify(runner).execute(42L);
    }

    @Test
    void duplicateActiveRunReturnsConflictWithoutStartingAnotherWorker() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        SyncRunner runner = mock(SyncRunner.class);
        ReviewMailService mail = mock(ReviewMailService.class);
        CloudApiProperties cloud = new CloudApiProperties();
        cloud.setBearerToken("test-token");
        when(db.queryForObject(anyString(), eq(Long.class), eq("MANUAL")))
                .thenThrow(new DataIntegrityViolationException("active source"));
        SyncService sync = new SyncService(db, runner, mail, cloud, false, "0 0 3 * * *", "");
        assertThatThrownBy(() -> sync.start("MANUAL"))
                .isInstanceOf(VersionConflictException.class);
        verify(runner, never()).execute(anyLong());
    }
}
