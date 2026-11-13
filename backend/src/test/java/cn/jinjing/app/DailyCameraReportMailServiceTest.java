package cn.jinjing.app;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCameraReportMailServiceTest {
    @Test
    void reportIncludesCountAndSubmissionDetails() {
        var rows = List.<Map<String, Object>>of(Map.of(
                "id", 23L, "nickname", "司机甲", "content", "路口南向北", "status", "PENDING",
                "report_time", "2026-11-12 02:30:00"));

        String report = DailyCameraReportMailService.render(LocalDate.of(2026, 11, 12), rows);

        assertThat(report).contains("上报数量：1 条", "#23", "司机甲", "待审核", "路口南向北");
    }

    @Test
    void emptyReportStillStatesZeroCount() {
        assertThat(DailyCameraReportMailService.render(LocalDate.of(2026, 11, 12), List.of()))
                .contains("上报数量：0 条", "昨日没有新的用户上报");
    }
}
