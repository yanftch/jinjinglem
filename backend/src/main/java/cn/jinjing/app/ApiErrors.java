package cn.jinjing.app;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

class ResourceMissingException extends RuntimeException {
    ResourceMissingException(String message) { super(message); }
}

class VersionConflictException extends RuntimeException {
    VersionConflictException(String message) { super(message); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResourceMissingException.class)
    ResponseEntity<Map<String, String>> missing(ResourceMissingException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<Map<String, String>> conflict(VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
