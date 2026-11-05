package cn.jinjing.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
class CameraReportController {
    private final CameraReportService reports;

    CameraReportController(CameraReportService reports) { this.reports = reports; }

    record ApprovalInput(@NotBlank @Size(max = 300) String name,
                         @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                         @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                         @Size(max = 50) String typeCodeRaw,
                         boolean coordinateConfirmed,
                         boolean publishScreenshot) {}

    record RejectionInput(@NotBlank @Size(max = 500) String reason) {}

    @PostMapping(value = "/api/v1/camera-reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> submit(@RequestParam String nickname, @RequestParam String content,
                               @RequestParam MultipartFile screenshot) throws IOException {
        return reports.submit(nickname, content, screenshot);
    }

    @GetMapping("/api/v1/admin/camera-reports")
    List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(defaultValue = "30") int limit) {
        return reports.list(status, offset, limit);
    }

    @GetMapping("/api/v1/admin/camera-reports/{id}")
    Map<String, Object> detail(@PathVariable long id) { return reports.detail(id); }

    @GetMapping("/api/v1/admin/camera-reports/{id}/screenshot")
    ResponseEntity<byte[]> screenshot(@PathVariable long id) { return image(reports.screenshot(id)); }

    @GetMapping("/api/v1/cameras/{id}/reported-image")
    ResponseEntity<byte[]> approvedScreenshot(@PathVariable long id) {
        return image(reports.approvedScreenshot(id));
    }

    private ResponseEntity<byte[]> image(CameraReportService.ImageData image) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.type())).body(image.bytes());
    }

    @PostMapping("/api/v1/admin/camera-reports/{id}/approve")
    Map<String, Object> approve(@PathVariable long id, @Valid @RequestBody ApprovalInput input,
                                Authentication authentication) {
        return reports.approve(id, input, authentication == null ? "admin" : authentication.getName());
    }

    @PostMapping("/api/v1/admin/camera-reports/{id}/reject")
    Map<String, Object> reject(@PathVariable long id, @Valid @RequestBody RejectionInput input,
                               Authentication authentication) {
        return reports.reject(id, input.reason(), authentication == null ? "admin" : authentication.getName());
    }
}
