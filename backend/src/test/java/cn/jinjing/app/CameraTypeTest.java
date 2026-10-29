package cn.jinjing.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CameraTypeTest {
    @Test
    void classifiesByNameWithDefinedPriority() {
        assertThat(CameraType.fromName("路口 已废弃【六环外】")).isEqualTo(CameraType.DISCARD);
        assertThat(CameraType.fromName("路口【六环外】")).isEqualTo(CameraType.SIX_RING_OUT);
        assertThat(CameraType.fromName("路口 二环内")).isEqualTo(CameraType.TWO_RING_IN);
        assertThat(CameraType.fromName("路口 南向北")).isEqualTo(CameraType.SIX_RING_IN);
    }

    @Test
    void ignoresInvisibleFormattingCharacters() {
        assertThat(CameraType.fromName("路口 六\u200b环外")).isEqualTo(CameraType.SIX_RING_OUT);
    }
}
