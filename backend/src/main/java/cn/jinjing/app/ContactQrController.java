package cn.jinjing.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ContactQrController {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate db;

    ContactQrController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/api/v1/contact/wechat-qr")
    ResponseEntity<byte[]> image() {
        List<Map<String, Object>> rows = db.queryForList("SELECT content_type,image_data FROM contact_qr WHERE id=1");
        if (rows.isEmpty()) throw new ResourceMissingException("微信二维码尚未配置");
        Map<String, Object> row = rows.get(0);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) row.get("content_type")))
                .cacheControl(CacheControl.noStore()).body((byte[]) row.get("image_data"));
    }

    @PostMapping(path="/api/v1/admin/contact/wechat-qr", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(@RequestParam("file") MultipartFile file, Authentication authentication) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw new MemberBadRequest("请选择不超过 2MB 的 PNG 或 JPEG 二维码图片");
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new MemberBadRequest("二维码图片不能超过 2MB");
        String type = imageType(bytes);
        if (type == null) throw new MemberBadRequest("二维码图片只支持 PNG 或 JPEG");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new MemberBadRequest("二维码图片无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096)
                    throw new MemberBadRequest("二维码尺寸不能超过 4096 × 4096");
                if (reader.read(0) == null) throw new MemberBadRequest("二维码图片无法识别");
            } finally { reader.dispose(); }
        } catch (IOException e) {
            throw new MemberBadRequest("二维码图片损坏或格式无效");
        }
        db.update("""
                INSERT INTO contact_qr(id,content_type,image_data,updated_by)
                VALUES (1,?,?,?)
                ON CONFLICT (id) DO UPDATE SET content_type=EXCLUDED.content_type,
                  image_data=EXCLUDED.image_data,updated_by=EXCLUDED.updated_by,updated_at=now()
                """, type, bytes, authentication == null ? "local-dev" : authentication.getName());
        return Map.of("configured", true, "contentType", type, "size", bytes.length);
    }

    private static String imageType(byte[] data) {
        if (data.length >= 8 && (data[0]&255)==137 && data[1]==80 && data[2]==78 && data[3]==71
                && data[4]==13 && data[5]==10 && data[6]==26 && data[7]==10) return MediaType.IMAGE_PNG_VALUE;
        if (data.length >= 3 && (data[0]&255)==255 && (data[1]&255)==216 && (data[2]&255)==255)
            return MediaType.IMAGE_JPEG_VALUE;
        return null;
    }
}
