package cn.jinjing.app;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named="REVIEW_TEST_DB_URL", matches=".+")
@SpringBootTest(properties="app.auth-enabled=false")
class ContactQrIntegrationTest {
    @Autowired ContactQrController controller;
    @Autowired JdbcTemplate db;

    @Test
    void adminUploadReplacesPublicImageAndRejectsInvalidData() throws Exception {
        byte[] png = image("png");
        var actor = new UsernamePasswordAuthenticationToken("adsmaxa", "unused");
        controller.upload(new MockMultipartFile("file", "qr.png", "image/png", png), actor);
        assertThat(controller.image().getBody()).containsExactly(png);
        assertThat(controller.image().getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(db.queryForObject("SELECT updated_by FROM contact_qr WHERE id=1", String.class)).isEqualTo("adsmaxa");

        byte[] jpg = image("jpg");
        controller.upload(new MockMultipartFile("file", "qr.jpg", "image/jpeg", jpg), actor);
        assertThat(controller.image().getBody()).containsExactly(jpg);
        assertThat(db.queryForObject("SELECT count(*) FROM contact_qr", Long.class)).isEqualTo(1);

        byte[] before = controller.image().getBody();
        assertThatThrownBy(() -> controller.upload(new MockMultipartFile("file", "bad.txt", "image/png",
                UUID.randomUUID().toString().getBytes()), actor)).isInstanceOf(MemberBadRequest.class);
        assertThat(controller.image().getBody()).containsExactly(before);
    }

    private byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        var bitmap = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(bitmap, format, out);
        return out.toByteArray();
    }
}
