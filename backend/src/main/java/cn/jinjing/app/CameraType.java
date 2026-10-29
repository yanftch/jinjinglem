package cn.jinjing.app;

import java.text.Normalizer;

enum CameraType {
    DISCARD,
    SIX_RING_OUT,
    TWO_RING_IN,
    SIX_RING_IN;

    static CameraType fromName(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\p{Z}\\s]+", "");
        if (normalized.contains("已废弃")) return DISCARD;
        if (normalized.contains("六环外")) return SIX_RING_OUT;
        if (normalized.contains("二环内")) return TWO_RING_IN;
        return SIX_RING_IN;
    }
}
