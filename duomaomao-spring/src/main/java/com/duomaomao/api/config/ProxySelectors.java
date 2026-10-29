package com.duomaomao.api.config;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * 强制直连的 {@link ProxySelector}。
 *
 * <p>为什么需要它：macOS 上的 JVM 启动时会把系统代理设置写进
 * {@code http.proxyHost / https.proxyHost / socksProxyHost} 系统属性。
 * 本机开着 Clash / Surge 这类工具时没问题，但工具退出后端口还在、
 * 进程没了，请求就会立刻挂在 {@code Can't connect to SOCKS proxy: Connection refused}，
 * 表现为「上游网络错误」，而且跟代码完全无关。</p>
 *
 * <p>我们的上游是公网 CDN，正常应该直连，所以默认走这个 selector；
 * 真要经过代理时把 {@code duomaomao.use-system-proxy} 设成 true。</p>
 */
public final class ProxySelectors {

    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // 直连失败没有可切换的代理，交给 HttpClient 抛原始异常
        }
    };

    private ProxySelectors() {
    }

    public static ProxySelector direct() {
        return DIRECT;
    }
}
