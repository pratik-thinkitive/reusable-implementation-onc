package com.onc.C2C3.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface QRDAAggregationService {

    ResponseEntity<?> importC2Patients(MultipartFile zipFile);

    ResponseEntity<?> generateQrdaIIISummary(List<String> patientIds, String measurementPeriodStart, String measurementPeriodEnd);
}


