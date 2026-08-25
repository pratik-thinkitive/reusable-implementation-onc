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
 * Turns G2 endpoint failures into responses, replacing the try/catch every endpoint carried.
 * Scoped to the G2 controllers - left open it would also govern EHR and QRDA.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = {G2Controller.class, PatientAccessAdminController.class})
public class G2ExceptionHandler {

    /** Create, grant and revoke failures. requestId and status stay null, as they did before. */
    @ExceptionHandler(AccessOperationException.class)
    public ResponseEntity<AccessRequestResponse> handleAccessOperation(AccessOperationException ex) {
        log.error("Access operation failed", ex);

        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(false);
        response.setMessage(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /** Plain text is unusual for this API, but it is what this endpoint has always returned. */
    @ExceptionHandler(PatientDataAccessException.class)
    public ResponseEntity<String> handlePatientDataAccess(PatientDataAccessException ex) {
        log.error("Error processing medical details access request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing request");
    }

    /**
     * Anything else: 500 with no body, matching the listing, data and dashboard endpoints.
     *
     * <p>Spring's own request failures already carry the right 4xx, so they are re-thrown - a
     * handler that re-throws counts as "not handled" and Spring falls back to its own.
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
     * Did Spring reject the request itself, rather than our code failing?
     *
     * <p>Most such exceptions implement ErrorResponse, but a failed parameter conversion arrives
     * as a TypeMismatchException, which does not - hence the explicit list.
     */
    private boolean isSpringRequestFailure(Exception ex) {
        return ex instanceof ErrorResponse
                || ex instanceof TypeMismatchException
                || ex instanceof BindException
                || ex instanceof HttpMessageConversionException;
    }
}
