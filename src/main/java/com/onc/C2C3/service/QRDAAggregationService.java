package com.onc.C2C3.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface QRDAAggregationService {

    ResponseEntity<Map<String, Object>> importC2Patients(MultipartFile zipFile);

    ResponseEntity<byte[]> generateQrdaIIISummary(List<String> patientIds, String measurementPeriodStart, String measurementPeriodEnd);
}


