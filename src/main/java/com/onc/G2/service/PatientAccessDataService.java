package com.onc.G2.service;

import com.onc.G2.dto.PatientAccessDataDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface PatientAccessDataService {

    // Initialize or update patient access data within reporting period
    PatientAccessDataDto initializePatientData(String patientFhirId, String patientId, String firstName, String lastName,
                                                  Integer organisationId, String providerId, String tinId,
                                                  LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd);

    void updateDenominator(String patientFhirId, LocalDate reportingPeriodStart, 
                          LocalDate reportingPeriodEnd, Instant encounterDate);

    void updateNumerator(String patientFhirId, LocalDate reportingPeriodStart, 
                        LocalDate reportingPeriodEnd, boolean hasAccess, Instant accessDate);

    //Decrement numerator (when access is revoked or not provided)
    void decrementNumerator(String patientFhirId, LocalDate reportingPeriodStart, 
                           LocalDate reportingPeriodEnd);

    //Get aggregated data for TIN
    PatientAccessDataDto getTinData(String tinId, LocalDate reportingPeriodStart,
                                       LocalDate reportingPeriodEnd);

    //Get aggregated data for TIN and Provider combination
    PatientAccessDataDto getTinProviderData(String tinId, String providerId, LocalDate reportingPeriodStart,
                                               LocalDate reportingPeriodEnd);

    //Get all patient data within reporting period
    List<PatientAccessDataDto> getAllPatientData(LocalDate reportingPeriodStart,
                                                    LocalDate reportingPeriodEnd);

    //Get patients with access (numerator = 1) for dashboard
    List<PatientAccessDataDto> getAccessGrantedPatientsFiltered(Integer organisationId, String providerId, String tinId,
                                                                LocalDate reportingPeriodStart,
                                                                LocalDate reportingPeriodEnd);

    //Get patients with access (numerator = 1) for group dashboard
    List<PatientAccessDataDto> getAccessGrantedPatientsForGroup(String tinId,
                                                                LocalDate reportingPeriodStart,
                                                                LocalDate reportingPeriodEnd);

     // Calculate percentage for data
    Double calculatePercentage(Integer numerator, Integer denominator);

}
