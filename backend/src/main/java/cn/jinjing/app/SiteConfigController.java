package cn.jinjing.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteConfigController {
    @Value("${app.amap-js-key:}")
    private String amapJsKey;

    @Value("${app.amap-security-js-code:}")
    private String amapSecurityJsCode;

    @Value("${app.map-coordinate-system:UNKNOWN}")
    private String mapCoordinateSystem;

    public record MapConfig(String key, String securityJsCode, String coordinateSystem) {}

    @GetMapping("/api/v1/site/map-config")
    public MapConfig mapConfig() {
        return new MapConfig(amapJsKey, amapSecurityJsCode, mapCoordinateSystem);
    }
}
