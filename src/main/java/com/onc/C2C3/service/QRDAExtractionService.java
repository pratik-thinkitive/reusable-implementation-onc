package com.onc.C2C3.service;

import com.onc.EHR.dto.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;
import java.util.List;

public interface QRDAExtractionService {

    ExtractedQrdaData extractPatientData(InputStream xmlInput) throws Exception;

    ExtractedProviderDetails extractProviderDetails(InputStream xmlInput) throws Exception;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ExtractedQrdaData {
        private PersonalDetailsData personalDetailsData;
        private List<InsuranceDetails> insuranceDetails;
        private AppointmentData appointmentData;
        private String clinicId;
        private String measureId;
        private String measureName;
        private FormResponse formResponse; // Contains Assessment and Intervention sections with CodeSection
        private DoctorDetailsData providerDetails;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ExtractedProviderDetails {
        private DoctorDetailsData providerDetails;
    }
}

