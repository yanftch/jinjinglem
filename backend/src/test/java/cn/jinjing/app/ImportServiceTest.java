package cn.jinjing.app;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

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

    @Test
    void ignoresBracketedStatusWhenMatchingAChangedCamera() {
        assertThat(ImportService.normalizeName("路口 南向北【已废弃】"))
                .isEqualTo(ImportService.normalizeName("路口【南向北】"));
    }

    @Test
    void onlyNearbySameNameCandidatesAreEligibleForAutomaticMerge() {
        BigDecimal lng = new BigDecimal("116.500000");
        BigDecimal lat = new BigDecimal("40.100000");
        assertThat(ImportService.distanceMeters(lng, lat, new BigDecimal("116.500200"), new BigDecimal("40.100100")))
                .isLessThan(100);
        assertThat(ImportService.distanceMeters(lng, lat, new BigDecimal("116.510000"), lat))
                .isGreaterThan(100);
    }
}
