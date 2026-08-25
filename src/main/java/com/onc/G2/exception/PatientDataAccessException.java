package com.onc.G2.exception;

/**
 * Something failed while checking whether a patient may view their health information.
 *
 * <p>No detail reaches the caller on this path - it answers with a fixed message - but the
 * cause is carried so it still reaches the logs.
 */
public class PatientDataAccessException extends RuntimeException {

    public PatientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
