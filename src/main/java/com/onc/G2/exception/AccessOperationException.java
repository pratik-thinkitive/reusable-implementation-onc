package com.onc.G2.exception;

/**
 * An access-request operation (create, grant or revoke) failed unexpectedly.
 *
 * <p>The message goes back to the caller verbatim, so it names the operation. Services throw
 * this because the service knows which operation was attempted; the handler does not.
 */
public class AccessOperationException extends RuntimeException {

    public AccessOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
