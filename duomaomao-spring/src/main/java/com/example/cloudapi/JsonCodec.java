package com.example.cloudapi;

import java.util.List;
import java.util.Map;

/**
 * Isolates JSON handling from the encrypted transport.
 *
 * <p>Use {@link JacksonJsonCodec} in Spring Boot. The built-in {@link MinimalJsonCodec}
 * can serialize ordinary JSON-compatible request objects but deliberately does not deserialize
 * arbitrary response objects.</p>
 */
public interface JsonCodec {
    String write(Object value) throws CloudApiException;

    <T> T read(String json, Class<T> targetType) throws CloudApiException;

    /** Minimal JDK-only encoder for Map/List/String/Number/Boolean/null request values. */
    final class MinimalJsonCodec implements JsonCodec {
        @Override
        public String write(Object value) throws CloudApiException {
            StringBuilder out = new StringBuilder();
            append(value, out);
            return out.toString();
        }

        @Override
        public <T> T read(String json, Class<T> targetType) throws CloudApiException {
            if (targetType == String.class) {
                return targetType.cast(json);
            }
            throw new CloudApiException(
                    "MinimalJsonCodec only supports String response bodies. "
                            + "Use JacksonJsonCodec in Spring Boot for typed JSON responses.");
        }

        @SuppressWarnings("unchecked")
        private static void append(Object value, StringBuilder out) throws CloudApiException {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                quote(text, out);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new CloudApiException("JSON object keys must be strings");
                    }
                    if (!first) out.append(',');
                    quote(key, out);
                    out.append(':');
                    append(entry.getValue(), out);
                    first = false;
                }
                out.append('}');
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    append(list.get(i), out);
                }
                out.append(']');
            } else {
                throw new CloudApiException("Unsupported JSON value type: " + value.getClass().getName());
            }
        }

        private static void quote(String value, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }
    }
}
