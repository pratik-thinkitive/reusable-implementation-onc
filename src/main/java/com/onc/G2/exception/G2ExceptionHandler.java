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

/** Maps G2 failures to responses. Scoped to G2 so EHR and QRDA keep their own error handling. */
@Slf4j
@RestControllerAdvice(assignableTypes = {G2Controller.class, PatientAccessAdminController.class})
public class G2ExceptionHandler {

    @ExceptionHandler(AccessOperationException.class)
    public ResponseEntity<AccessRequestResponse> handleAccessOperation(AccessOperationException ex) {
        log.error("Access operation failed", ex);

        AccessRequestResponse response = new AccessRequestResponse();
        response.setSuccess(false);
        response.setMessage(ex.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /** This endpoint answers in plain text rather than JSON. */
    @ExceptionHandler(PatientDataAccessException.class)
    public ResponseEntity<String> handlePatientDataAccess(PatientDataAccessException ex) {
        log.error("Error processing medical details access request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing request");
    }

    /**
     * Everything else is a 500 with no body. Spring's own request failures already carry the
     * right 4xx, so re-throwing hands them back to Spring instead of masking them.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleUnexpected(Exception ex) throws Exception {
        if (isSpringRequestFailure(ex)) {
            throw ex;
        }

        log.error("Unhandled error in a G2 endpoint", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /** Most of these implement ErrorResponse, but a failed conversion does not. */
    private boolean isSpringRequestFailure(Exception ex) {
        return ex instanceof ErrorResponse
                || ex instanceof TypeMismatchException
                || ex instanceof BindException
                || ex instanceof HttpMessageConversionException;
    }
}
