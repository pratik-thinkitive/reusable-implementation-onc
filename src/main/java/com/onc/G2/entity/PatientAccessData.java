package com.onc.G2.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

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

    @JsonFormat(pattern = "yyyy-dd-MM")
    @Column(name = "reporting_period_start", nullable = false)
    private LocalDateTime reportingPeriodStart;

    @JsonFormat(pattern = "yyyy-dd-MM")
    @Column(name = "reporting_period_end", nullable = false)
    private LocalDateTime reportingPeriodEnd;

    @Column(name = "denominator_count", nullable = false)
    private Integer denominatorCount = 0;

    @Column(name = "numerator_count", nullable = false)
    private Integer numeratorCount = 0;

    @Column(name = "has_access_granted")
    private Boolean hasAccessGranted = false;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "access_granted_date")
    private LocalDateTime accessGrantedDate;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "access_revoked_date")
    private LocalDateTime accessRevokedDate;

    @Column(name = "is_numerator_recorded")
    private Boolean isNumeratorRecorded = false;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "first_encounter_date")
    private LocalDateTime firstEncounterDate;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-dd-MM HH:mm:ss")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
