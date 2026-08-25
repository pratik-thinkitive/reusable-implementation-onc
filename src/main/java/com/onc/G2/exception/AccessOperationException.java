package com.onc.G2.exception;

/**
 * An access-request operation (creating, granting or revoking) failed unexpectedly.
 *
 * <p>The message is written for the caller and is returned to them verbatim, so it carries the
 * name of the operation that failed - for example {@code "Error granting access: ..."}. Services
 * throw this instead of letting a raw exception escape, because the service is the layer that
 * knows which operation was being attempted; the handler that turns it into a response does not.
 */
public class AccessOperationException extends RuntimeException {

    public AccessOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
