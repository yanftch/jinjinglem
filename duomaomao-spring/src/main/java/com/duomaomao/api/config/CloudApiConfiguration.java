package com.duomaomao.api.config;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.cloudapi.EncryptedCloudApiClient;
import com.example.cloudapi.JacksonJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CloudApiConfiguration {

    /**
     * IDEA 里点运行即可的约定位置（相对当前工作目录，通常是模块根目录）。
     * 优先级低于显式的 {@code DUOMAOMAO_BEARER_TOKEN(_FILE)}。
     */
    private static final String[] DEFAULT_TOKEN_LOCATIONS = {
            "apikey.txt",
            "../_work/apikey.txt",
            "_work/apikey.txt",
    };

    @Bean
    public EncryptedCloudApiClient encryptedCloudApiClient(CloudApiProperties properties, ObjectMapper objectMapper) {
        String token = resolveToken(properties);
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    // macOS 的 JVM 会带出系统代理；默认直连，理由见 ProxySelectors
                    .proxy(properties.isUseSystemProxy()
                            ? ProxySelector.getDefault()
                            : ProxySelectors.direct())
                    .build();
            return EncryptedCloudApiClient.builder()
                    .baseUri(URI.create(properties.getBaseUrl()))
                    .bearerToken(token)
                    .rsaPublicKeyBase64(properties.getPublicKeyBase64())
                    .jsonCodec(new JacksonJsonCodec(UpstreamJson.lenient(objectMapper)))
                    .httpClient(httpClient)
                    .connectTimeout(properties.getConnectTimeout())
                    .requestTimeout(properties.getRequestTimeout())
                    .disableCache(true)
                    .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("无法解析 duomaomao.public-key-base64，请检查配置", e);
        }
    }

    private String resolveToken(CloudApiProperties properties) {
        String inline = properties.getBearerToken() == null ? "" : properties.getBearerToken().trim();
        if (!inline.isEmpty()) {
            return inline;
        }
        String file = properties.getBearerTokenFile() == null ? "" : properties.getBearerTokenFile().trim();
        if (!file.isEmpty()) {
            return readToken(Path.of(file), true);
        }
        // 方便在 IDEA 里直接点运行：没配环境变量时，按约定位置找一下
        for (String candidate : DEFAULT_TOKEN_LOCATIONS) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isReadable(path)) {
                return readToken(path, true);
            }
        }
        throw new IllegalStateException("""
                缺少 Bearer Token。
                三种方式任选其一：
                  1) IDEA Run Configuration 里加环境变量：
                       DUOMAOMAO_BEARER_TOKEN_FILE=/abs/path/to/_work/apikey.txt
                  2) export DUOMAOMAO_BEARER_TOKEN="$(cat _work/apikey.txt)"
                  3) 把 apikey.txt 放到下列任一位置（会自动识别）：
                       %s
                """.formatted(String.join("\n                       ", DEFAULT_TOKEN_LOCATIONS)));
    }

    private String readToken(Path path, boolean required) {
        try {
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                throw new IllegalStateException("Token 文件是空的: " + path);
            }
            return token;
        } catch (IOException e) {
            if (!required) {
                return null;
            }
            throw new IllegalStateException("读取 Bearer Token 失败: " + path, e);
        }
    }
}
