package com.onc.G2.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "patient_access_request")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@RequiredArgsConstructor
public class PatientAccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "patient_fhir_id", nullable = false)
    private String patientFhirId;

    @Column(name = "patient_id")
    private String patientId;

    @Column(name = "organisation_id")
    private Integer organisationId;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "tin_id")
    private String tinId;

    @Column(name = "request_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private RequestType requestType;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "access_granted_at")
    private LocalDateTime accessGrantedAt;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "access_revoked_at")
    private LocalDateTime accessRevokedAt;

    @JsonFormat(pattern = "yyyy-dd-MM")
    @Column(name = "reporting_period_start")
    private LocalDateTime reportingPeriodStart;

    @JsonFormat(pattern = "yyyy-dd-MM")
    @Column(name = "reporting_period_end")
    private LocalDateTime reportingPeriodEnd;

    @Column(name = "is_first_encounter")
    private Boolean isFirstEncounter;

    @Column(name = "encounter_id")
    private String encounterId;

    public enum RequestType {
        MEDICAL_DETAILS_ACCESS,
        PERSONAL_DETAILS_ACCESS,
    }

    public enum RequestStatus {
        PENDING,
        ACCESS_GRANTED,
        ACCESS_REVOKED
    }
}
