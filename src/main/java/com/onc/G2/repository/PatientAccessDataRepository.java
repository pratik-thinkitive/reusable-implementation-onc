package com.onc.G2.repository;

import com.onc.G2.entity.PatientAccessData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientAccessDataRepository extends JpaRepository<PatientAccessData, Long> {

    Optional<PatientAccessData> findByPatientFhirIdAndReportingPeriodStartAndReportingPeriodEnd(
            String patientFhirId, 
            LocalDate reportingPeriodStart, 
            LocalDate reportingPeriodEnd );

    @Query("SELECT pad FROM PatientAccessData pad " +
            "WHERE pad.tinId = :tinId " +
            "AND pad.providerId = :providerId " +
            "AND pad.reportingPeriodStart <= :reportingPeriodEnd " +
            "AND pad.reportingPeriodEnd >= :reportingPeriodStart")
    List<PatientAccessData> findAllByTinAndProviderWithinPeriod(
            @Param("tinId") String tinId,
            @Param("providerId") String providerId,
            @Param("reportingPeriodStart") LocalDate reportingPeriodStart,
            @Param("reportingPeriodEnd") LocalDate reportingPeriodEnd);

    @Query("SELECT pad FROM PatientAccessData pad " +
            "WHERE pad.tinId = :tinId " +
            "AND pad.reportingPeriodStart <= :reportingPeriodEnd " +
            "AND pad.reportingPeriodEnd >= :reportingPeriodStart")
    List<PatientAccessData> findByTinIdWithinReportingPeriod(
            @Param("tinId") String tinId,
            @Param("reportingPeriodStart") LocalDate reportingPeriodStart,
            @Param("reportingPeriodEnd") LocalDate reportingPeriodEnd);

    @Query("SELECT pad FROM PatientAccessData pad " +
            "WHERE pad.reportingPeriodStart <= :reportingPeriodStart " +
            "AND pad.reportingPeriodEnd >= :reportingPeriodEnd")
    List<PatientAccessData> findAllPatientsWithinReportingPeriod(LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd);

    @Query("SELECT pad FROM PatientAccessData pad " +
            "WHERE (pad.hasAccessGranted = true OR (pad.hasAccessGranted = false AND pad.isNumeratorRecorded = true)) " +
            "AND pad.denominatorCount > 0 " +
            "AND pad.reportingPeriodStart <= :reportingPeriodEnd " +
            "AND pad.reportingPeriodEnd >= :reportingPeriodStart")
    List<PatientAccessData> getAccessGrantedPatients(
            @Param("reportingPeriodStart") LocalDate reportingPeriodStart,
            @Param("reportingPeriodEnd") LocalDate reportingPeriodEnd);

    @Query("SELECT pad FROM PatientAccessData pad " +
            "WHERE pad.tinId = :tinId " +
            "AND (pad.hasAccessGranted = true OR (pad.hasAccessGranted = false AND pad.isNumeratorRecorded = true)) " +
            "AND pad.denominatorCount > 0 " +
            "AND pad.reportingPeriodStart <= :reportingPeriodEnd " +
            "AND pad.reportingPeriodEnd >= :reportingPeriodStart")
    List<PatientAccessData> getAccessGrantedPatientsByTinId(
            @Param("tinId") String tinId,
            @Param("reportingPeriodStart") LocalDate reportingPeriodStart,
            @Param("reportingPeriodEnd") LocalDate reportingPeriodEnd);

}
