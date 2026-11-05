package cn.jinjing.app;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DailyCommentServiceTest {
    @Test
    void reportGroupsCommentsUnderCameraHeadings() {
        var rows = List.<Map<String, Object>>of(
                Map.of("camera_name", "甲路口", "nickname", "司机甲", "comment_time", "2026-11-04 20:00:00", "content", "新增评论一"),
                Map.of("camera_name", "乙路口", "nickname", "司机乙", "comment_time", "2026-11-04 19:00:00", "content", "新增评论二"),
                Map.of("camera_name", "甲路口", "nickname", "司机丙", "comment_time", "2026-11-04 18:00:00", "content", "新增评论三"));
        String report = DailyCommentService.renderReport(LocalDate.of(2026, 11, 4), rows);
        assertThat(report).contains("统计日期：2026-11-04", "新增评论：3 条", "# 甲路口", "# 乙路口");
        assertThat(report.split("# 甲路口", -1)).hasSize(2);
        assertThat(report.indexOf("新增评论三")).isLessThan(report.indexOf("# 乙路口"));
    }
}
