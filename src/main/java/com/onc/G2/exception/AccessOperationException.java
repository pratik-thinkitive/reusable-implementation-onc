package com.onc.G2.exception;

/** A create, grant or revoke failed. The message reaches the caller as-is, so it names the operation. */
public class AccessOperationException extends RuntimeException {

    public AccessOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
