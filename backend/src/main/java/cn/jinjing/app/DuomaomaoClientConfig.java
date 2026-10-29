package cn.jinjing.app;

import com.duomaomao.api.config.CloudApiConfiguration;
import com.duomaomao.api.config.CloudApiProperties;
import com.duomaomao.api.service.DuomaomaoService;
import com.duomaomao.api.service.ExportService;
import com.example.cloudapi.EncryptedCloudApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Only the crawler's service layer is embedded; its controllers are never scanned. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudApiProperties.class)
class DuomaomaoClientConfig {
    @Bean
    @Lazy
    EncryptedCloudApiClient duomaomaoClient(CloudApiProperties properties, ObjectMapper mapper) {
        return new CloudApiConfiguration().encryptedCloudApiClient(properties, mapper);
    }

    @Bean
    @Lazy
    DuomaomaoService duomaomaoService(EncryptedCloudApiClient client, CloudApiProperties properties) {
        return new DuomaomaoService(client, properties);
    }

    @Bean
    @Lazy
    ExportService duomaomaoExportService(DuomaomaoService api, CloudApiProperties properties, ObjectMapper mapper) {
        return new ExportService(api, properties, mapper);
    }
}
