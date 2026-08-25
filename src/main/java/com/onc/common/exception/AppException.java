package com.onc.common.exception;

import com.onc.api.support.ResponseCode;
import lombok.Getter;

/**
 * The single exception business code throws.
 *
 * <p>Unchecked, matching the exceptions this codebase already used, so service signatures stay
 * free of {@code throws} noise. The trade is that nothing reminds a caller a method can fail -
 * every failure path is expected to end at {@link com.onc.api.GlobalExceptionHandler} rather
 * than in a local {@code catch}.
 *
 * <p>The message reaches the client verbatim, so it must be safe to display: no stack traces,
 * SQL, exception class names, or upstream hostnames.
 */
@Getter
public class AppException extends RuntimeException {

    private final ResponseCode errorCode;

    /** Fields this failure is attributed to, surfaced in {@code ApiResponse.errors}. */
    private final String[] fields;

    public AppException(ResponseCode code, String message, String... fields) {
        super(message);
        this.errorCode = code;
        this.fields = fields == null ? new String[0] : fields;
    }

    /** Keeps the cause for the log while the caller still sees only {@code message}. */
    public AppException(ResponseCode code, String message, Throwable cause, String... fields) {
        super(message, cause);
        this.errorCode = code;
        this.fields = fields == null ? new String[0] : fields;
    }
}
