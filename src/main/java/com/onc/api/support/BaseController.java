package com.onc.api.support;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Builds success envelopes. Controllers never construct a {@link ResponseEntity} by hand, and
 * never catch an exception to turn it into a response - that is the global handler's job.
 */
public abstract class BaseController {

    protected <T> ResponseEntity<ApiResponse<T>> data(T entity) {
        return respond(ResponseCode.ENTITY, null, entity);
    }

    protected <T> ResponseEntity<ApiResponse<T>> data(ResponseCode code, String message, T entity) {
        return respond(code, message, entity);
    }

    protected <T> ResponseEntity<ApiResponse<T>> success(ResponseCode code, String message, T entity) {
        return respond(code, message, entity);
    }

    protected ResponseEntity<ApiResponse<Void>> success(ResponseCode code, String message) {
        return respond(code, message, null);
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(ResponseCode code, String message, T body) {
        ApiResponse<T> envelope = ApiResponse.of(code, message, body);
        envelope.setPath(currentPath());
        return new ResponseEntity<>(envelope, code.status());
    }

    private static String currentPath() {
        try {
            return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                    .getRequest()
                    .getRequestURI();
        } catch (IllegalStateException notAWebRequest) {
            return null;
        }
    }
}
