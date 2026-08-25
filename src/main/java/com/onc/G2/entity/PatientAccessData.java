package com.onc.G2.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "patient_access_data")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@RequiredArgsConstructor
public class PatientAccessData {

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
    @Column(name = "reporting_period_start", nullable = false)
    private LocalDate reportingPeriodStart;
    @Column(name = "reporting_period_end", nullable = false)
    private LocalDate reportingPeriodEnd;

    @Column(name = "denominator_count", nullable = false)
    private Integer denominatorCount = 0;

    @Column(name = "numerator_count", nullable = false)
    private Integer numeratorCount = 0;

    @Column(name = "has_access_granted")
    private Boolean hasAccessGranted = false;
    @Column(name = "access_granted_date")
    private Instant accessGrantedDate;
    @Column(name = "access_revoked_date")
    private Instant accessRevokedDate;

    @Column(name = "is_numerator_recorded")
    private Boolean isNumeratorRecorded = false;
    @Column(name = "first_encounter_date")
    private Instant firstEncounterDate;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
