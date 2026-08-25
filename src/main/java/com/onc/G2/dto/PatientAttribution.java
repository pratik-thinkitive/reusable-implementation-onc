package com.onc.G2.dto;

import lombok.Data;

/**
 * A patient's identity and who they are attributed to, as read from the EHR.
 *
 * <p>Every field may be null: a failed lookup returns an empty instance rather than throwing,
 * which is how the endpoints have always behaved.
 */
@Data
public class PatientAttribution {

    private String firstName;
    private String lastName;
    private Integer organisationId;
    private String providerId;
    private String tinId;
}
