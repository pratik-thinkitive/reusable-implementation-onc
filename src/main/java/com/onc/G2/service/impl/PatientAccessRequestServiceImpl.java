package com.onc.G2.service.impl;

import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.dto.PatientAccessRequestDto;
import com.onc.G2.entity.PatientAccessRequest;
import com.onc.G2.repository.PatientAccessRequestRepository;
import com.onc.G2.service.PatientAccessRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PatientAccessRequestServiceImpl implements PatientAccessRequestService {

    private final PatientAccessRequestRepository patientAccessRequestRepository;

    @Override
    public PatientAccessRequestDto createAccessRequest(String patientFhirId, String patientId, String firstName, String lastName,
                                                      Integer organisationId, String providerId, String tinId,
                                                      PatientAccessRequest.RequestType requestType,
                                                      String encounterId, Boolean isFirstEncounter,
                                                      LocalDateTime reportingPeriodStart, LocalDateTime reportingPeriodEnd) {
        
        log.info("Creating access request for patient: {} with type: {} for encounter: {} (provider: {}, tin: {})", patientFhirId, requestType, encounterId, providerId, tinId);
        
        // Check if patient already has a GRANTED request for this provider/TIN combination (regardless of encounter)
        if (requestType == PatientAccessRequest.RequestType.MEDICAL_DETAILS_ACCESS) {
            Optional<PatientAccessRequest> existingAccessForProviderTin = patientAccessRequestRepository
                    .findByPatientFhirIdAndRequestTypeAndProviderIdAndTinId(patientFhirId, requestType, providerId, tinId);

            if (existingAccessForProviderTin.isPresent()) {
                PatientAccessRequest existingRequest = existingAccessForProviderTin.get();
                PatientAccessRequest.RequestStatus existingStatus = existingRequest.getStatus();

                if (existingStatus == PatientAccessRequest.RequestStatus.ACCESS_GRANTED) {
                    log.info("Patient: {} already has ACCESS_GRANTED for provider: {} and TIN: {} (existing encounter: {}). " + "Blocking new request for encounter: {}",
                            patientFhirId, providerId, tinId, existingRequest.getEncounterId(), encounterId);

                    PatientAccessRequestDto dto = convertToDto(existingRequest);
                    dto.setDuplicateRequest(true);
                    dto.setDuplicateMessage("You already have access to the health information from your prior appointments.");
                    return dto;
                }

                if (existingStatus == PatientAccessRequest.RequestStatus.ACCESS_REVOKED) {
                    log.info("Patient: {} has ACCESS_REVOKED for provider: {} and TIN: {} (existing encounter: {}). " + "Blocking new request for encounter: {}",
                            patientFhirId, providerId, tinId, existingRequest.getEncounterId(), encounterId);

                    PatientAccessRequestDto dto = convertToDto(existingRequest);
                    dto.setDuplicateRequest(true);
                    dto.setDuplicateMessage("Your access to health information is revoked, so you are unable to request access.");
                    return dto;
                }
            }
        }
        
        // For other request types OR if no existing access found, check for duplicate encounter
        Optional<PatientAccessRequest> existingRequestForEncounter = patientAccessRequestRepository.findByPatientFhirIdAndEncounterIdAndRequestType(patientFhirId, encounterId, requestType);
        
        if (existingRequestForEncounter.isPresent()) {
            log.warn("Request already exists for patient: {} with type: {} for this specific encounter: {}", patientFhirId, requestType, encounterId);
            // Return a DTO indicating duplicate request for same encounter
            PatientAccessRequestDto dto = convertToDto(existingRequestForEncounter.get());
            dto.setDuplicateRequest(true);
            dto.setDuplicateMessage("You have already requested access for this encounter. Request status: " + existingRequestForEncounter.get().getStatus());
            return dto;
        }

        PatientAccessRequest accessRequest = new PatientAccessRequest();
        accessRequest.setPatientFhirId(patientFhirId);
        accessRequest.setFirstName(firstName);
        accessRequest.setLastName(lastName);
        accessRequest.setPatientId(patientId);
        accessRequest.setOrganisationId(organisationId);
        accessRequest.setProviderId(providerId);
        accessRequest.setTinId(tinId);
        accessRequest.setRequestType(requestType);
        accessRequest.setStatus(PatientAccessRequest.RequestStatus.PENDING);
        accessRequest.setRequestedAt(LocalDateTime.now());
        accessRequest.setIsFirstEncounter(isFirstEncounter);
        accessRequest.setEncounterId(encounterId);
        accessRequest.setReportingPeriodStart(reportingPeriodStart);
        accessRequest.setReportingPeriodEnd(reportingPeriodEnd);

        PatientAccessRequest savedRequest = patientAccessRequestRepository.save(accessRequest);
        log.info("Created access request with ID: {} for patient: {} and encounter: {}", savedRequest.getId(), patientFhirId, encounterId);
        
        return convertToDto(savedRequest);
    }


    @Override
    public AccessRequestResponse grantAccess(Long requestId) {
        log.info("Granting access for request: {}", requestId);
        
        Optional<PatientAccessRequest> requestOpt = patientAccessRequestRepository.findById(requestId);
        if (requestOpt.isEmpty()) {
            return AccessRequestResponse.builder()
                    .success(false)
                    .message("Access request not found")
                    .build();
        }

        PatientAccessRequest request = requestOpt.get();
        if (request.getStatus() != PatientAccessRequest.RequestStatus.PENDING) {
            return AccessRequestResponse.builder()
                    .success(false)
                    .message("Request must be in pending status to grant access")
                    .build();
        }

        request.setStatus(PatientAccessRequest.RequestStatus.ACCESS_GRANTED);
        request.setAccessGrantedAt(LocalDateTime.now());

        patientAccessRequestRepository.save(request);
        log.info("Granted access for request: {}", requestId);

        return AccessRequestResponse.builder()
                .success(true)
                .message("Access granted successfully")
                .requestId(requestId.toString())
                .status("ACCESS_GRANTED")
                .build();
    }

    @Override
    public AccessRequestResponse revokeAccess(Long requestId) {
        log.info("Revoking access for request: {} ", requestId);
        
        Optional<PatientAccessRequest> requestOpt = patientAccessRequestRepository.findById(requestId);
        if (requestOpt.isEmpty()) {
            return AccessRequestResponse.builder()
                    .success(false)
                    .message("Access request not found")
                    .build();
        }

        PatientAccessRequest request = requestOpt.get();
        if (request.getStatus() != PatientAccessRequest.RequestStatus.ACCESS_GRANTED) {
            return AccessRequestResponse.builder()
                    .success(false)
                    .message("Request must be in ACCESS_GRANTED status to revoke")
                    .build();
        }

        request.setStatus(PatientAccessRequest.RequestStatus.ACCESS_REVOKED);
        request.setAccessRevokedAt(LocalDateTime.now());

        patientAccessRequestRepository.save(request);
        log.info("Revoked access for request: {}", requestId);

        return AccessRequestResponse.builder()
                .success(true)
                .message("Access revoked successfully")
                .requestId(requestId.toString())
                .status("ACCESS_REVOKED")
                .build();
    }

    @Override
    public boolean hasActiveAccess(String patientFhirId, PatientAccessRequest.RequestType requestType) {
        Optional<PatientAccessRequest> activeRequest = patientAccessRequestRepository.findCurrentActiveAccess(patientFhirId, requestType);
        return activeRequest.isPresent();
    }

    @Override
    public List<PatientAccessRequestDto> getPendingRequests(Integer organisationId, String providerId, String tinId) {
        List<PatientAccessRequest> requests = patientAccessRequestRepository
                .findByOrganisationIdAndProviderIdAndTinIdAndStatus(
                        organisationId, providerId, tinId,
                        PatientAccessRequest.RequestStatus.PENDING);

        return requests.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<PatientAccessRequestDto> getGrantedRequests(Integer organisationId, String providerId, String tinId) {
        List<PatientAccessRequest> requests = patientAccessRequestRepository
                .findByOrganisationIdAndProviderIdAndTinIdAndStatus(
                        organisationId, providerId, tinId,
                        PatientAccessRequest.RequestStatus.ACCESS_GRANTED);

        return requests.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<PatientAccessRequestDto> getRevokedRequests(Integer organisationId, String providerId, String tinId) {

        List<PatientAccessRequest> requests = patientAccessRequestRepository
                .findByOrganisationIdAndProviderIdAndTinIdAndStatus(
                        organisationId, providerId, tinId,
                        PatientAccessRequest.RequestStatus.ACCESS_REVOKED);

        return requests.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

    }

    @Override
    public PatientAccessRequestDto getAccessRequestById(Long requestId) {
        return patientAccessRequestRepository.findById(requestId)
                .map(this::convertToDto)
                .orElse(null);
    }

    @Override
    public List<PatientAccessRequestDto> getPatientAccessRequests(String patientFhirId) {
        return patientAccessRequestRepository.findByPatientFhirId(patientFhirId)
                .stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    private PatientAccessRequestDto convertToDto(PatientAccessRequest request) {
        PatientAccessRequestDto dto = new PatientAccessRequestDto();
        dto.setId(request.getId());
        dto.setPatientFhirId(request.getPatientFhirId());
        dto.setPatientId(request.getPatientId());
        dto.setFirstName(request.getFirstName());
        dto.setLastName(request.getLastName());
        dto.setOrganisationId(request.getOrganisationId());
        dto.setProviderId(request.getProviderId());
        dto.setTinId(request.getTinId());
        dto.setEncounterId(request.getEncounterId());
        dto.setRequestType(request.getRequestType());
        dto.setStatus(request.getStatus());
        dto.setRequestedAt(request.getRequestedAt());
        dto.setAccessGrantedAt(request.getAccessGrantedAt());
        dto.setAccessRevokedAt(request.getAccessRevokedAt());
        dto.setReportingPeriodStart(request.getReportingPeriodStart());
        dto.setReportingPeriodEnd(request.getReportingPeriodEnd());
        dto.setIsFirstEncounter(request.getIsFirstEncounter());
        return dto;
    }
}
