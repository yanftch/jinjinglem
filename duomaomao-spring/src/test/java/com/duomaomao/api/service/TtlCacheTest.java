package com.duomaomao.api.service;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    @Test
    void returnsValueBeforeExpiryAndNullAfter() throws Exception {
        TtlCache<String> cache = new TtlCache<>(4);

        cache.put("k", "v", Duration.ofMillis(80));
        assertThat(cache.get("k")).isEqualTo("v");

        Thread.sleep(150);
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void ignoresNullValuesAndNonPositiveTtl() {
        TtlCache<String> cache = new TtlCache<>(4);

        cache.put("k1", null, Duration.ofMinutes(1));
        cache.put("k2", "v", Duration.ZERO);

        assertThat(cache.size()).isZero();
    }

    @Test
    void clearsWhenCapacityExceeded() {
        TtlCache<String> cache = new TtlCache<>(2);

        cache.put("a", "1", Duration.ofMinutes(1));
        cache.put("b", "2", Duration.ofMinutes(1));
        cache.put("c", "3", Duration.ofMinutes(1));

        assertThat(cache.size()).isLessThanOrEqualTo(2);
    }
}
