package com.onc.common.service;

import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for services that reject requests on business rules. Subclasses call {@link #throwError}
 * instead of building a response - the transport layer is not their concern.
 */
public abstract class AppService {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected void throwError(ResponseCode code, String message) {
        logger.warn("Business rule rejected the request: [{}] {}", code, message);
        throw new AppException(code, message);
    }

    protected void throwError(ResponseCode code, String message, Throwable cause) {
        logger.warn("Business rule rejected the request: [{}] {}", code, message);
        throw new AppException(code, message, cause);
    }
}
