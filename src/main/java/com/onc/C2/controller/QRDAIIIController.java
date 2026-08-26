package com.onc.C2.controller;

import com.onc.C2.dto.PatientData;
import com.onc.C2.service.QRDAExtractionService;
import com.onc.api.support.ApiResponse;
import com.onc.api.support.BaseController;
import com.onc.api.support.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

//takes all the files as input and return the QRDA-III file in response which contains the summary of the patient records
@RestController
@RequestMapping("/ehr/c2")
@RequiredArgsConstructor
@Slf4j
public class QRDAIIIController extends BaseController {

    private final QRDAExtractionService qrdaExtractionService;

    @PostMapping(value = "/import", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<ApiResponse<PatientData>> importQrda(@RequestBody String qrdaXml) {
        log.info("Importing a QRDA document of {} characters", qrdaXml.length());
        InputStream xmlStream = new ByteArrayInputStream(qrdaXml.getBytes(StandardCharsets.UTF_8));
        return data(ResponseCode.OK, "QRDA Document Imported", qrdaExtractionService.extractPatientData(xmlStream));
    }
}
