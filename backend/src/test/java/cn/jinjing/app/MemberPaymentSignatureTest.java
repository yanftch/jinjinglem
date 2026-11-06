package cn.jinjing.app;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MemberPaymentSignatureTest {
    @Test
    void hmacSha256MatchesKnownVector() {
        assertThat(MemberPaymentService.hmac("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
