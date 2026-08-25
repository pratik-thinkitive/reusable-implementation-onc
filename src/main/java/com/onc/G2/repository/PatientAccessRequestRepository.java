package com.onc.G2.repository;

import com.onc.G2.entity.PatientAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientAccessRequestRepository extends JpaRepository<PatientAccessRequest, Long> {

    @Query("SELECT par FROM PatientAccessRequest par WHERE par.patientFhirId = :patientFhirId " +
            "AND par.requestType = :requestType " +
            "AND par.status = 'ACCESS_GRANTED' " +
            "AND par.accessGrantedAt IS NOT NULL " +
            "AND par.accessRevokedAt IS NULL")
    Optional<PatientAccessRequest> findCurrentActiveAccess(
            @Param("patientFhirId") String patientFhirId,
            @Param("requestType") PatientAccessRequest.RequestType requestType);

    @Query("SELECT r FROM PatientAccessRequest r WHERE r.organisationId = :organisationId " +
            "AND r.providerId = :providerId " +
            "AND r.tinId = :tinId " +
            "AND r.status = :status")
    List<PatientAccessRequest> findByOrganisationIdAndProviderIdAndTinIdAndStatus(
            @Param("organisationId") Integer organisationId,
            @Param("providerId") String providerId,
            @Param("tinId") String tinId,
            @Param("status") PatientAccessRequest.RequestStatus status);

    List<PatientAccessRequest> findByPatientFhirId(String patientFhirId);

    // Find existing request for same patient, encounter, and request type
    Optional<PatientAccessRequest> findByPatientFhirIdAndEncounterIdAndRequestType(String patientFhirId, String encounterId, PatientAccessRequest.RequestType requestType);

    // Used to check if patient already has access for this provider/TIN combination
    @Query("SELECT par FROM PatientAccessRequest par WHERE par.patientFhirId = :patientFhirId AND par.requestType = :requestType AND par.providerId = :providerId AND par.tinId = :tinId")
    Optional<PatientAccessRequest> findByPatientFhirIdAndRequestTypeAndProviderIdAndTinId(
            @Param("patientFhirId") String patientFhirId, @Param("requestType") PatientAccessRequest.RequestType requestType,
            @Param("providerId") String providerId, @Param("tinId") String tinId);
}
