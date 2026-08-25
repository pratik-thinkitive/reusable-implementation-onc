package com.onc.G2.exception;

/** An access check failed. The caller gets a fixed message; the cause is kept for the logs. */
public class PatientDataAccessException extends RuntimeException {

    public PatientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
