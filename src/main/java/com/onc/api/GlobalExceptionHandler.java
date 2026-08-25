package com.onc.api;

import com.onc.api.support.ApiResponse;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
// Spring Data 4.1 moved this out of org.springframework.data.mapping.
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns every failure - ours or the framework's - into the same {@link ApiResponse} envelope.
 *
 * <p>Applies to all three modules. It replaces the G2-scoped advice that used to re-throw
 * Spring's own request failures to keep their 4xx: {@link #handleExceptionInternal} now
 * re-shapes those responses instead of letting them ship an RFC 7807 body.
 *
 * <p>Not covered: authentication and authorization failures. Spring Security's filter chain runs
 * before the dispatcher servlet, so once security is added this class must be paired with an
 * {@code AuthenticationEntryPoint} and an {@code AccessDeniedHandler} writing the same envelope.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Off in every deployed environment; on locally to shorten the debug loop. */
    @Value("${onc.expose-error-details:false}")
    private boolean exposeErrorDetails;

    // ---------------------------------------------------------------- our own exception

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Object> handleAppException(AppException ex, WebRequest request) {
        ResponseCode code = ex.getErrorCode();
        Map<String, String> fieldErrors = fieldsOf(ex);

        if (code.status().is5xxServerError()) {
            log.error("[{}] {}", code, ex.getMessage(), ex);
        } else {
            log.warn("[{}] {}", code, ex.getMessage());
        }

        return respond(ex, code, ex.getMessage(), fieldErrors, request);
    }

    private Map<String, String> fieldsOf(AppException ex) {
        if (ex.getFields() == null || ex.getFields().length == 0) {
            return null;
        }
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (String field : ex.getFields()) {
            fieldErrors.putIfAbsent(field, ex.getMessage());
        }
        return fieldErrors;
    }

    // ---------------------------------------------------------------- request validation

    /** Bean-validation failure on an {@code @Valid @RequestBody}. Reports every field, not the first. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        String message = fieldErrors.values().stream().findFirst().orElse("Invalid request data.");
        log.info("Validation failed: {} field error(s)", fieldErrors.size());

        return respond(ex, ResponseCode.VALIDATION_FAILED, message, fieldErrors, request);
    }

    /** Constraints declared directly on controller method parameters. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().stream()
                    .findFirst()
                    .ifPresent(error -> fieldErrors.putIfAbsent(
                            name == null ? "request" : name, error.getDefaultMessage()));
        }

        String message = fieldErrors.values().stream().findFirst().orElse("Invalid request.");
        return respond(ex, ResponseCode.VALIDATION_FAILED, message, fieldErrors, request);
    }

    /** {@code @Validated} on collection elements and service-level constraints. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fieldErrors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));

        String message = fieldErrors.values().stream().findFirst().orElse("Invalid request.");
        return respond(ex, ResponseCode.VALIDATION_FAILED, message, fieldErrors, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        // Name the parameter - "Request could not be processed." tells an integrator nothing.
        String message = "Required parameter '" + ex.getParameterName() + "' is missing.";
        return respond(
                ex,
                ResponseCode.BAD_REQUEST,
                message,
                Map.of(ex.getParameterName(), message),
                request);
    }

    /**
     * A query parameter or path variable that will not convert - an unknown enum name, a date
     * that is not ISO. Spring's own message names the target Java class, so it is replaced.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            org.springframework.beans.TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ex.getPropertyName();
        String field = name == null ? "request" : name;
        String message = describeExpectedType(field, ex.getRequiredType());

        return respond(ex, ResponseCode.BAD_REQUEST, message, Map.of(field, message), request);
    }

    private String describeExpectedType(String field, Class<?> requiredType) {
        if (requiredType == null) {
            return "Invalid value for '" + field + "'.";
        }
        if (requiredType.isEnum()) {
            return "Invalid value for '%s'. Allowed: %s.".formatted(field, allowedValues(requiredType));
        }
        if (java.time.temporal.Temporal.class.isAssignableFrom(requiredType)) {
            return "'%s' must be a valid date in yyyy-MM-dd format.".formatted(field);
        }
        return "'%s' must be a valid %s.".formatted(field, requiredType.getSimpleName());
    }

    private String allowedValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(String::valueOf).collect(Collectors.joining(", "));
    }

    /**
     * Unparseable body. Jackson's own message names Java classes and packages, so it is never
     * passed through - it is translated into something a client can act on.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        log.warn("Unreadable request body (details server-side only)", ex);

        String message = "Request body contains invalid data. Please check field formats.";

        if (ex.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null) {
            String field = ife.getPath().isEmpty()
                    ? "field"
                    : ife.getPath().get(ife.getPath().size() - 1).getPropertyName();
            message = describeExpectedType(field == null ? "field" : field, ife.getTargetType());
        }

        return respond(ex, ResponseCode.BAD_REQUEST, message, null, request);
    }

    // ---------------------------------------------------------------- persistence

    /** A raw message carries the constraint name, the table and often the offending value. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(
            DataIntegrityViolationException ex, WebRequest request) {

        log.error("Data integrity violation", ex);
        return respond(
                ex,
                ResponseCode.CONFLICT,
                "This operation conflicts with existing data.",
                null,
                request);
    }

    /** Two callers saved the same row. A 409 lets the client refresh; a 500 tells them nothing. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, WebRequest request) {

        return respond(
                ex,
                ResponseCode.CONFLICT,
                "Another user has updated this record. Please refresh.",
                null,
                request);
    }

    /** A client-supplied sort field names both the property and the entity class if echoed. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Object> handlePropertyReference(
            PropertyReferenceException ex, WebRequest request) {

        String message = "Invalid sort or filter field '" + ex.getPropertyName() + "'.";
        return respond(ex, ResponseCode.BAD_REQUEST, message, null, request);
    }

    // ---------------------------------------------------------------- catch-all

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAnythingElse(Exception ex, WebRequest request) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        log.error("Unhandled {}: {}", cause.getClass().getName(), cause.getMessage(), ex);

        String message = exposeErrorDetails
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : "Something went wrong. Please try again later.";

        return respond(ex, ResponseCode.INTERNAL_ERROR, message, null, request);
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * The critical override. The base class builds a {@link ProblemDetail} for its own handlers
     * (unsupported method, unknown route, unreadable body, ...) - an RFC 7807 body that looks
     * nothing like the envelope. Without this a handful of 4xx responses ship a different shape.
     * Bodies our own handlers already built pass through untouched.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        if (body == null || body instanceof ProblemDetail) {
            String message = "Request could not be processed.";
            if (body instanceof ProblemDetail detail) {
                message = detail.getDetail() != null ? detail.getDetail() : detail.getTitle();
            }
            body = envelope(statusToCode(statusCode), message, null, request);
        }

        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(
            Exception ex,
            ResponseCode code,
            String message,
            Map<String, String> errors,
            WebRequest request) {

        return handleExceptionInternal(
                ex,
                envelope(code, message, errors, request),
                new HttpHeaders(),
                code.status(),
                request);
    }

    private ApiResponse<Void> envelope(
            ResponseCode code, String message, Map<String, String> errors, WebRequest request) {

        ApiResponse<Void> body = errors == null
                ? ApiResponse.error(code, message)
                : ApiResponse.validationErrors(message, errors);

        // validationErrors always stamps VALIDATION_FAILED; keep the caller's code when it differs.
        body.setCode(code.name());
        body.setSuccess(code.isSuccess());
        body.setPath(request.getDescription(false).replaceFirst("^uri=", ""));
        return body;
    }

    private static ResponseCode statusToCode(HttpStatusCode status) {
        int value = status.value();
        if (value == HttpStatus.NOT_FOUND.value()) return ResponseCode.NOT_FOUND;
        if (value == HttpStatus.UNAUTHORIZED.value()) return ResponseCode.UNAUTHORIZED;
        if (value == HttpStatus.FORBIDDEN.value()) return ResponseCode.ACCESS_DENIED;
        if (value == HttpStatus.METHOD_NOT_ALLOWED.value()) return ResponseCode.METHOD_NOT_ALLOWED;
        if (value == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) return ResponseCode.UNSUPPORTED_MEDIA_TYPE;
        if (value >= 500) return ResponseCode.INTERNAL_ERROR;
        return ResponseCode.BAD_REQUEST;
    }
}
