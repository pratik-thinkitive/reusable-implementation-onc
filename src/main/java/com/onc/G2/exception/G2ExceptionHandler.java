package com.onc.G2.exception;

import com.onc.G2.controller.G2Controller;
import com.onc.G2.controller.PatientAccessAdminController;
import com.onc.G2.dto.AccessRequestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures from the G2 endpoints into HTTP responses, in one place.
 *
 * <p>Before this existed, all fourteen G2 endpoints wrapped their body in an identical
 * {@code try/catch} that logged and returned a 500. That boilerplate is gone: the controllers now
 * describe only the successful path, and anything that goes wrong arrives here.
 *
 * <p><b>Scope.</b> {@code assignableTypes} deliberately limits this advice to the two G2
 * controllers. Without that, it would also govern the EHR and QRDA controllers and silently
 * change how <em>their</em> errors are reported.
 *
 * <p><b>Responses are unchanged.</b> Each handler reproduces exactly what the controller it
 * replaced used to return, down to the wording and the empty bodies.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = {G2Controller.class, PatientAccessAdminController.class})
public class G2ExceptionHandler {

    /**
     * Failures from creating, granting or revoking access.
     *
     * <p>Answers 500 with the standard {@link AccessRequestResponse} body. {@code requestId} and
     * {@code status} stay null, exactly as they did when the controller built this by hand.
     */
    @ExceptionHandler(AccessOperationException.class)
    public ResponseEntity<AccessRequestResponse> handleAccessOperation(AccessOperationException ex) {
        log.error("Access operation failed", ex);

        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(false);
        response.setMessage(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Failures while checking whether a patient may view their information.
     *
     * <p>Answers 500 with a plain-text body. That is unusual for this API, but it is what the
     * endpoint has always returned, so it is preserved rather than tidied.
     */
    @ExceptionHandler(PatientDataAccessException.class)
    public ResponseEntity<String> handlePatientDataAccess(PatientDataAccessException ex) {
        log.error("Error processing medical details access request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing request");
    }

    /**
     * Anything else: 500 with no body, matching the listing, data and dashboard endpoints.
     *
     * <p>Spring raises its own exceptions when it cannot make sense of a request - an unparseable
     * date, a missing required parameter - and already answers those with the right 4xx. Those
     * must not be swallowed into a 500 here, so they are re-thrown. Spring treats a handler that
     * re-throws as "not handled" and falls back to its built-in behaviour, so the caller still
     * gets the same 4xx with an empty body as before.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleUnexpected(Exception ex) throws Exception {
        if (isSpringRequestFailure(ex)) {
            throw ex;
        }

        log.error("Unhandled error in a G2 endpoint", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * Is this Spring telling us the request itself was malformed, rather than our own code
     * failing?
     *
     * <p>Most such exceptions implement {@link ErrorResponse}, but not all of them do - a failed
     * parameter conversion arrives as a {@link TypeMismatchException}, which does not. Each entry
     * below is a family of "the caller sent something we could not use", and every one of them
     * already carries a 4xx status that this handler must not override.
     */
    private boolean isSpringRequestFailure(Exception ex) {
        return ex instanceof ErrorResponse                  // most Spring MVC exceptions
                || ex instanceof TypeMismatchException      // a parameter would not convert
                || ex instanceof BindException              // request binding failed
                || ex instanceof HttpMessageConversionException; // body could not be read/written
    }
}
