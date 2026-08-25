package com.onc.G2.exception;

/**
 * Something went wrong while deciding whether a patient may view their health information.
 *
 * <p>Unlike {@link AccessOperationException}, no detail is passed back to the caller - this path
 * answers with a fixed message. The cause is still carried so it reaches the logs.
 */
public class PatientDataAccessException extends RuntimeException {

    public PatientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
