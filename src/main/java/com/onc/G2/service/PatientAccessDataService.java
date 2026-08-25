package com.onc.G2.service;

import com.onc.G2.dto.PatientAccessDataDto;

import java.time.LocalDateTime;
import java.util.List;

public interface PatientAccessDataService {

    // Initialize or update patient access data within reporting period
    PatientAccessDataDto initializePatientData(String patientFhirId, String patientId, String firstName, String lastName,
                                                  Integer organisationId, String providerId, String tinId,
                                                  LocalDateTime reportingPeriodStart, LocalDateTime reportingPeriodEnd);

    void updateDenominator(String patientFhirId, LocalDateTime reportingPeriodStart, 
                          LocalDateTime reportingPeriodEnd, LocalDateTime encounterDate);

    void updateNumerator(String patientFhirId, LocalDateTime reportingPeriodStart, 
                        LocalDateTime reportingPeriodEnd, boolean hasAccess, LocalDateTime accessDate);


    //Decrement numerator (when access is revoked or not provided)
    void decrementNumerator(String patientFhirId, LocalDateTime reportingPeriodStart, 
                           LocalDateTime reportingPeriodEnd);

    //Get aggregated data for TIN
    PatientAccessDataDto getTinData(String tinId, LocalDateTime reportingPeriodStart,
                                       LocalDateTime reportingPeriodEnd);

    //Get aggregated data for TIN and Provider combination
    PatientAccessDataDto getTinProviderData(String tinId, String providerId, LocalDateTime reportingPeriodStart,
                                               LocalDateTime reportingPeriodEnd);

    //Get all patient data within reporting period
    List<PatientAccessDataDto> getAllPatientData(LocalDateTime reportingPeriodStart,
                                                    LocalDateTime reportingPeriodEnd);

    //Get patients with access (numerator = 1) for dashboard
    List<PatientAccessDataDto> getAccessGrantedPatientsFiltered(Integer organisationId, String providerId, String tinId,
                                                                LocalDateTime reportingPeriodStart,
                                                                LocalDateTime reportingPeriodEnd);

    //Get patients with access (numerator = 1) for group dashboard
    List<PatientAccessDataDto> getAccessGrantedPatientsForGroup(String tinId,
                                                                LocalDateTime reportingPeriodStart,
                                                                LocalDateTime reportingPeriodEnd);

     // Calculate percentage for data
    Double calculatePercentage(Integer numerator, Integer denominator);

}
