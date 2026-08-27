package com.onc.C2C3.controller;

import com.onc.C2C3.service.QRDAAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ehr/c2")
public class QRDAIIIController {

    private final QRDAAggregationService qrdaAggregationService;

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importC2Patients(@RequestParam("file") MultipartFile zipFile) {
        return qrdaAggregationService.importC2Patients(zipFile);
    }

    @PostMapping("/summary")
    public ResponseEntity<byte[]> generateC2Summary(@RequestBody List<String> patientIds,
                                                    @RequestParam("measurementPeriodStart") String measurementPeriodStart,
                                                    @RequestParam("measurementPeriodEnd") String measurementPeriodEnd) {
        return qrdaAggregationService.generateQrdaIIISummary(patientIds, measurementPeriodStart, measurementPeriodEnd);
    }
}


