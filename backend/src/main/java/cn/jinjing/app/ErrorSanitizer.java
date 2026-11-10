package cn.jinjing.app;

import java.util.regex.Pattern;

final class ErrorSanitizer {
    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)(access_token|refresh_token|token|secret|app_key|appkey|api_key|authorization|code)(=|%3d|:)([^&\\s,;\\]}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~-]{12,}");

    private ErrorSanitizer() {}

    static String sanitize(String message) {
        if (message == null || message.isBlank()) return "未知错误";
        String safe = SECRET_PARAMETER.matcher(message).replaceAll("$1$2[已隐藏]");
        safe = BEARER.matcher(safe).replaceAll("$1[已隐藏]");
        return safe.substring(0, Math.min(500, safe.length()));
    }
}
