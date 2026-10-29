package com.example.cloudapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Java 17 client for an HTTP API using an RSA-wrapped ephemeral AES-GCM response envelope.
 *
 * <p>The class intentionally contains no target-specific endpoint, credential, or application
 * identifier. Supply values only for a server that you own or are explicitly authorized to call.</p>
 *
 * <p>This implementation is thread-safe. Each request uses a new 32-byte AES key.</p>
 */
public final class EncryptedCloudApiClient {
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;

    private final URI baseUri;
    private final String bearerToken;
    private final PublicKey rsaPublicKey;
    private final HttpClient httpClient;
    private final JsonCodec jsonCodec;
    private final SecureRandom secureRandom;
    private final Duration requestTimeout;
    private final boolean disableCache;
    private final ConcurrentHashMap<String, String> endpointPaths = new ConcurrentHashMap<>();

    private EncryptedCloudApiClient(Builder builder) throws GeneralSecurityException {
        this.baseUri = normalizeBaseUri(builder.baseUri);
        this.bearerToken = requireNonBlank(builder.bearerToken, "bearerToken");
        this.rsaPublicKey = builder.rsaPublicKey != null
                ? builder.rsaPublicKey
                : parseRsaPublicKey(builder.rsaPublicKeyBase64);
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(builder.connectTimeout).build();
        this.jsonCodec = Objects.requireNonNull(builder.jsonCodec, "jsonCodec");
        this.secureRandom = builder.secureRandom != null ? builder.secureRandom : new SecureRandom();
        this.requestTimeout = builder.requestTimeout;
        this.disableCache = builder.disableCache;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Posts one encrypted request and decodes the encrypted JSON response. */
    public <T> T postEncrypted(String path, Map<String, ?> payload, Class<T> responseType)
            throws CloudApiException, IOException, InterruptedException {
        String plainJson = postEncryptedRaw(path, payload);
        return jsonCodec.read(plainJson, responseType);
    }

    /** Posts one encrypted request and returns the decrypted JSON string. */
    public String postEncryptedRaw(String path, Map<String, ?> payload)
            throws CloudApiException, IOException, InterruptedException {
        URI target = resolveUri(path);
        Envelope envelope = makeEnvelope(payload);
        HttpRequest request = request(target, envelope.json());
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode(), target, response.body());
        }
        return decryptResponse(response.body(), envelope.aesKey());
    }

    /**
     * Uses a cached endpoint path where available. On a 404, refreshes paths through the supplied
     * authorized refresh endpoint and retries once only when the path actually changed.
     */
    public <T> T postToEndpoint(
            String endpointKey,
            String fallbackPath,
            String refreshPath,
            Map<String, ?> payload,
            Class<T> responseType)
            throws CloudApiException, IOException, InterruptedException {
        String path = endpointPaths.getOrDefault(requireNonBlank(endpointKey, "endpointKey"),
                normalizePath(fallbackPath));
        try {
            return postEncrypted(path, payload, responseType);
        } catch (HttpStatusException e) {
            if (e.statusCode() != 404 || refreshPath == null || refreshPath.isBlank()) throw e;
            refreshPaths(refreshPath);
            String refreshed = endpointPaths.getOrDefault(endpointKey, path);
            if (refreshed.equals(path)) throw e;
            return postEncrypted(refreshed, payload, responseType);
        }
    }

    /**
     * Calls the authorized endpoint-path refresh operation and caches top-level entries under
     * the response's {@code paths} object. Requires a codec that can decode to Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> refreshPaths(String refreshPath)
            throws CloudApiException, IOException, InterruptedException {
        Map<?, ?> response = postEncrypted(refreshPath, Map.of(), Map.class);
        Object rawPaths = response.get("paths");
        if (!(rawPaths instanceof Map<?, ?> paths)) return Map.of();

        Map<String, String> updated = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : paths.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof String path) {
                String normalized = normalizePath(path);
                endpointPaths.put(key, normalized);
                updated.put(key, normalized);
            }
        }
        return Map.copyOf(updated);
    }

    /** Exposes an immutable snapshot of dynamically cached paths. */
    public Map<String, String> cachedPaths() {
        return Map.copyOf(endpointPaths);
    }

    /** Allows a trusted caller to preload a current endpoint path. */
    public void putEndpointPath(String endpointKey, String path) {
        endpointPaths.put(requireNonBlank(endpointKey, "endpointKey"), normalizePath(path));
    }

    /** Converts a Base64-encoded X.509 SubjectPublicKeyInfo RSA key to a Java PublicKey. */
    public static PublicKey parseRsaPublicKey(String x509Base64) throws GeneralSecurityException {
        String compact = requireNonBlank(x509Base64, "rsaPublicKeyBase64").replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(compact);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Utility for URL-safe creation of a path segment; do not use this for a complete path. */
    public static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Envelope makeEnvelope(Map<String, ?> payload) throws CloudApiException {
        try {
            byte[] rawKey = new byte[AES_KEY_BYTES];
            secureRandom.nextBytes(rawKey);
            SecretKey aesKey = new SecretKeySpec(rawKey, "AES");

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec params = new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            rsa.init(Cipher.ENCRYPT_MODE, rsaPublicKey, params);
            String ek = Base64.getEncoder().withoutPadding().encodeToString(rsa.doFinal(rawKey));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            if (payload != null) requestBody.putAll(payload);
            // Enforce a fresh client-created key rather than accepting an accidental caller value.
            requestBody.put("ek", ek);
            return new Envelope(aesKey, jsonCodec.write(requestBody));
        } catch (GeneralSecurityException e) {
            throw new CloudApiException("Unable to construct encrypted request envelope", e);
        }
    }

    private HttpRequest request(URI target, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (disableCache) {
            builder.header("Cache-Control", "no-cache, no-store, must-revalidate");
            builder.header("Pragma", "no-cache");
        }
        return builder.build();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static String decryptResponse(byte[] response, SecretKey aesKey) throws CloudApiException {
        byte[] frame = decodeBase64IfClearlyText(response);
        if (frame.length <= GCM_IV_BYTES + GCM_TAG_BYTES) {
            throw new CloudApiException("Encrypted response frame is too short: " + frame.length + " bytes");
        }
        try {
            byte[] iv = java.util.Arrays.copyOfRange(frame, 0, GCM_IV_BYTES);
            // Java's GCM API expects ciphertext followed by the authentication tag.
            byte[] ciphertextAndTag = new byte[frame.length - GCM_IV_BYTES];
            System.arraycopy(frame, GCM_IV_BYTES + GCM_TAG_BYTES, ciphertextAndTag, 0,
                    frame.length - GCM_IV_BYTES - GCM_TAG_BYTES);
            System.arraycopy(frame, GCM_IV_BYTES, ciphertextAndTag,
                    frame.length - GCM_IV_BYTES - GCM_TAG_BYTES, GCM_TAG_BYTES);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            return new String(inflateRaw(aes.doFinal(ciphertextAndTag)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CloudApiException("Unable to decrypt AES-GCM response", e);
        }
    }

    private static byte[] inflateRaw(byte[] compressed) throws CloudApiException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        byte[] buffer = new byte[8192];
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(compressed.length * 4, 65536))) {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() > MAX_DECOMPRESSED_BYTES - count) {
                        throw new CloudApiException("Decompressed response exceeds " + MAX_DECOMPRESSED_BYTES + " bytes");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new CloudApiException("Invalid or truncated raw-deflate response");
                }
            }
            return output.toByteArray();
        } catch (DataFormatException | IOException e) {
            throw new CloudApiException("Unable to raw-deflate decompress response", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decodeBase64IfClearlyText(byte[] input) {
        if (input.length == 0) return input;
        ByteArrayOutputStream compact = new ByteArrayOutputStream(input.length);
        for (byte item : input) {
            int value = item & 0xff;
            if (value == '\r' || value == '\n' || value == ' ' || value == '\t') continue;
            boolean base64 = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '+' || value == '/' || value == '=';
            if (!base64) return input;
            compact.write(value);
        }
        byte[] candidate = compact.toByteArray();
        try {
            return Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException ignored) {
            return input;
        }
    }

    private URI resolveUri(String path) throws CloudApiException {
        try {
            return baseUri.resolve(normalizePath(path));
        } catch (IllegalArgumentException e) {
            throw new CloudApiException("Invalid endpoint path", e);
        }
    }

    private static URI normalizeBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("baseUri must use HTTP or HTTPS");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("baseUri must include a host");
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static String normalizePath(String path) {
        String value = requireNonBlank(path, "path");
        if (value.contains("://") || value.startsWith("//")) {
            throw new IllegalArgumentException("Only relative endpoint paths are allowed");
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Envelope(SecretKey aesKey, String json) { }

    /** HTTP non-2xx response. Body is retained as bytes and not automatically logged. */
    public static final class HttpStatusException extends CloudApiException {
        private final int statusCode;
        private final URI uri;
        private final byte[] responseBody;

        private HttpStatusException(int statusCode, URI uri, byte[] responseBody) {
            super("HTTP " + statusCode + " from " + uri);
            this.statusCode = statusCode;
            this.uri = uri;
            this.responseBody = responseBody == null ? new byte[0] : responseBody.clone();
        }

        public int statusCode() { return statusCode; }
        public URI uri() { return uri; }
        public byte[] responseBody() { return responseBody.clone(); }
    }

    public static final class Builder {
        private URI baseUri;
        private String bearerToken;
        private PublicKey rsaPublicKey;
        private String rsaPublicKeyBase64;
        private HttpClient httpClient;
        private JsonCodec jsonCodec = new JsonCodec.MinimalJsonCodec();
        private SecureRandom secureRandom;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration requestTimeout = Duration.ofSeconds(120);
        private boolean disableCache;

        public Builder baseUri(URI value) { this.baseUri = value; return this; }
        public Builder bearerToken(String value) { this.bearerToken = value; return this; }
        public Builder rsaPublicKey(PublicKey value) { this.rsaPublicKey = value; return this; }
        public Builder rsaPublicKeyBase64(String value) { this.rsaPublicKeyBase64 = value; return this; }
        public Builder httpClient(HttpClient value) { this.httpClient = value; return this; }
        public Builder jsonCodec(JsonCodec value) { this.jsonCodec = value; return this; }
        public Builder secureRandom(SecureRandom value) { this.secureRandom = value; return this; }
        public Builder connectTimeout(Duration value) { this.connectTimeout = Objects.requireNonNull(value); return this; }
        public Builder requestTimeout(Duration value) { this.requestTimeout = Objects.requireNonNull(value); return this; }
        public Builder disableCache(boolean value) { this.disableCache = value; return this; }

        public EncryptedCloudApiClient build() throws GeneralSecurityException {
            if (rsaPublicKey != null && rsaPublicKeyBase64 != null && !rsaPublicKeyBase64.isBlank()) {
                throw new IllegalArgumentException("Configure either rsaPublicKey or rsaPublicKeyBase64, not both");
            }
            return new EncryptedCloudApiClient(this);
        }
    }
}
