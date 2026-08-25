package com.onc.G2.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onc.G2.enums.RequestStatus;
import com.onc.G2.enums.RequestType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

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
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "access_granted_at")
    private Instant accessGrantedAt;
    @Column(name = "access_revoked_at")
    private Instant accessRevokedAt;
    @Column(name = "reporting_period_start")
    private LocalDate reportingPeriodStart;
    @Column(name = "reporting_period_end")
    private LocalDate reportingPeriodEnd;

    @Column(name = "is_first_encounter")
    private Boolean isFirstEncounter;

    @Column(name = "encounter_id")
    private String encounterId;
}
