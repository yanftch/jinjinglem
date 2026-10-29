package com.duomaomao.api;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.duomaomao.api.service.DuomaomaoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "duomaomao.bearer-token=test-token-not-used")
class DuomaomaoApiApplicationTests {

    @Autowired
    private DuomaomaoService service;

    @Test
    void contextLoadsWithoutCallingUpstream() {
        assertThat(service).isNotNull();
        assertThat(service.cachedRouteCount()).isZero();
    }

    /** 两个页面都是「拖进 IDE 就能用」的一部分，别在打包时丢掉。 */
    @Test
    void staticPagesArePackaged() throws Exception {
        assertThat(read("/static/index.html")).contains("躲猫猫").contains("/api/v1/export/start");
        assertThat(read("/static/route.html")).contains("/api/v1/routes/plan").contains("isSuccessOnPlanRoute");
    }

    private String read(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
