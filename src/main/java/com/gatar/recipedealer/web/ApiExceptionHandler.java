package com.gatar.recipedealer.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import com.gatar.recipedealer.capture.InvalidCaptureException;
import com.gatar.recipedealer.lock.LockService;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidArgument(MethodArgumentNotValidException e) {
        var details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("invalid_request", "malformed JSON body"));
    }

    @ExceptionHandler(InvalidCaptureException.class)
    ResponseEntity<ApiError> invalidCapture(InvalidCaptureException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.error(), e.details()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiError.of("payload_too_large", "max 25 MB per file and 50 MB per request"));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> badMultipart(MultipartException e) {
        return ResponseEntity.badRequest().body(ApiError.of("invalid_request", "malformed multipart request"));
    }

    /** 409 with who holds the edit lock and until when. */
    @ExceptionHandler(LockService.LockedException.class)
    ResponseEntity<Map<String, Object>> locked(LockService.LockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "locked",
                "lockedBy", e.holder().lockedBy(), "expiresAt", e.holder().expiresAt()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiError.of(e.error(), e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", e.getMessage()));
    }
}
