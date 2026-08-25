package com.onc.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one envelope every endpoint and the global exception handler return.
 *
 * <p>{@code success} is derived from the code rather than passed in, so a body can never claim
 * success while carrying an error code.
 *
 * @param <T> the payload type; {@code Void} on failures
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    public static final String DEFAULT_VERSION = "1.0";

    private boolean success;
    private String code;
    private String message;

    /** Present on success only. */
    private T data;

    /** Field-keyed detail, present on validation failures only. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> errors;

    private String path;

    /** Quoted in support tickets; logged alongside the failure it describes. */
    private String requestId;

    @Builder.Default
    private String version = DEFAULT_VERSION;

    private OffsetDateTime timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return build(ResponseCode.OK, message, data, null);
    }

    public static <T> ApiResponse<T> created(T data, String message) {
        return build(ResponseCode.CREATED, message, data, null);
    }

    public static ApiResponse<Void> message(String message) {
        return build(ResponseCode.OK, message, null, null);
    }

    public static <T> ApiResponse<T> of(ResponseCode code, String message, T data) {
        return build(code, message, data, null);
    }

    public static <T> ApiResponse<T> error(ResponseCode code, String message) {
        return build(code, message, null, null);
    }

    public static <T> ApiResponse<T> validationErrors(String message, Map<String, String> errors) {
        return build(
                ResponseCode.VALIDATION_FAILED,
                message,
                null,
                errors == null ? null : new LinkedHashMap<>(errors));
    }

    private static <T> ApiResponse<T> build(
            ResponseCode code, String message, T data, Map<String, String> errors) {
        return ApiResponse.<T>builder()
                .success(code != null && code.isSuccess())
                .code(code == null ? null : code.name())
                .message(message)
                .data(data)
                .errors(errors)
                .requestId(UUID.randomUUID().toString())
                .version(DEFAULT_VERSION)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
