package com.onc.C2.service;

import com.onc.C2.dto.PatientData;

import java.io.InputStream;

// Extracts patient data from a QRDA Category I document for Category III aggregation.
// Throws exception when the document cannot be processed.
public interface QRDAExtractionService {

    PatientData extractPatientData(InputStream xmlInput);

    String formatDate(String value);
}
