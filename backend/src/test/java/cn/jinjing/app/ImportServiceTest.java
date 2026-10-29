package cn.jinjing.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportServiceTest {
    @Test
    void matchesNamesWithSpacingAndOptionalTags() {
        assertThat(ImportService.normalizeName("顺义区 香堤北街香堤中路交口 南向北【六环外】"))
                .isEqualTo(ImportService.normalizeName("顺义区香堤北街香堤中路交口 【南向北】"));
    }

    @Test
    void preservesDirectionInIdentity() {
        assertThat(ImportService.normalizeName("路口 南向北"))
                .isNotEqualTo(ImportService.normalizeName("路口 北向南"));
    }
}
