package com.duomaomao.api.config;

import java.net.Proxy;
import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：macOS 上系统代理进程退出后，JVM 仍会带上 {@code socksProxyHost=127.0.0.1:7890}，
 * 请求直接挂在 "Can't connect to SOCKS proxy"。默认使用的 selector 必须始终给 NO_PROXY。
 */
class ProxySelectorsTest {

    @Test
    void returnsNoProxyForAnyUri() {
        assertThat(ProxySelectors.direct().select(URI.create("https://example.com/x")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(ProxySelectors.direct().select(URI.create("http://127.0.0.1:8080/x")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(ProxySelectors.direct().select(URI.create("https://jinjing-0gxvttiv313dd8aa-1319848734.ap-shanghai.app.tcloudbase.com/x")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void isSingletonAndDoesNotThrowOnConnectFailure() {
        assertThat(ProxySelectors.direct()).isSameAs(ProxySelectors.direct());
        ProxySelectors.direct().connectFailed(
                URI.create("https://example.com"),
                new java.net.InetSocketAddress("127.0.0.1", 1),
                new java.io.IOException("boom"));
    }
}
