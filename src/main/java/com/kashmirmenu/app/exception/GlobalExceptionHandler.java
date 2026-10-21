package com.kashmirmenu.app.exception;

import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.common.response.ErrorResponse;
import com.kashmirmenu.app.common.response.FieldError;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateResourceException(
            DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .data(null)
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Lock conflicts that outlasted every retry (LockRetry). The request changed nothing, so asking again
     * is safe. 503 with Retry-After tells a client, and the person behind it, exactly that.
     */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleLockFailure(
            org.springframework.dao.PessimisticLockingFailureException ex) {
        log.warn("Lock conflict surfaced to the client: {}", ex.getClass().getSimpleName());
        ApiResponse<Object> response = ApiResponse.builder()
                .success(false)
                .message("The table is busy right now. Please try again in a moment.")
                .data(null)
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(response);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleConflictException(ConflictException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        ApiResponse<Object> response = ApiResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .data(null)
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Too many attempts refused");
        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(response);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(
            BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        List<FieldError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> FieldError.builder()
                        .field(error.getField())
                        .message(error.getDefaultMessage())
                        .build())
                .toList();

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message("Validation failed")
                .errors(errors)
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(
            IllegalArgumentException ex) {

        log.warn("Illegal argument: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFoundException(
            EntityNotFoundException ex) {

        log.warn("Entity not found: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }



    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex) {

        log.warn("Access denied: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    // Ownership checks throw the app's own AccessDeniedException (not Spring Security's);
    // without this they fell through to the generic handler and returned 500.
    @ExceptionHandler(com.kashmirmenu.app.exception.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleOwnershipDenied(
            com.kashmirmenu.app.exception.AccessDeniedException ex) {

        log.warn("Access denied: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException ex) {

        log.warn("Authentication failed: invalid credentials");

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message("Invalid email or password.")
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex) {

        log.warn("Authentication failed: {}", ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message("Authentication failed.")
                .build();

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex) {

        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message("The requested resource was not found.")
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(
            IllegalStateException ex) {

        log.warn("Illegal state: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false)
                .message(ex.getMessage())
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    // Unparseable JSON or an unknown enum value in a body used to fall through to the 500 handler.
    // An upload bigger than spring.servlet.multipart.max-file-size, a form with no file, or a request that
    // is not a multipart form at all. These used to fall through to the 500 handler.
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("Upload too large");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.builder()
                .success(false)
                .message("The file is too large (limit 10 MB).")
                .build());
    }

    @ExceptionHandler({
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<ErrorResponse> handleBadUpload(Exception ex) {
        log.warn("Bad upload request: {}", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .message("Please choose a photo to upload.")
                .build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .message("The request body is not valid.")
                .build());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Bad value for parameter {}", ex.getName());
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .message("Invalid value for '" + ex.getName() + "'.")
                .build());
    }

    /** A client calling a route with the wrong HTTP method is a client mistake (405), not a server fault. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("Method {} is not allowed on this route", ex.getMethod());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return response.body(ErrorResponse.builder()
                .success(false)
                .message("That method is not supported for this request.")
                .build());
    }

    /** A body in a content type the route does not read (415), likewise not a server fault. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported content type: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorResponse.builder()
                .success(false)
                .message("The request content type is not supported.")
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.error("Unexpected exception occurred.", ex);
        ErrorResponse response = ErrorResponse.builder()
                .success(false)
                .message("An unexpected error occurred.")
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}