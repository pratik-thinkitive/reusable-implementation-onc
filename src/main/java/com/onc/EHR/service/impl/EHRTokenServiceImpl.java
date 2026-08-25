package com.onc.EHR.service.impl;

import com.onc.EHR.dto.TokenResponse;
import com.onc.EHR.service.EHRTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Password-grant token retrieval against the EHR provider API.
 *
 * <p>Uses the shared {@code RestTemplate} bean rather than constructing one per call, which
 * is what the G2 copy of this class did and the QRDA copy did not.
 */
@Service
@RequiredArgsConstructor
public class EHRTokenServiceImpl implements EHRTokenService {

    @Value("${ehr.token.url}")
    private String tokenUrl;

    @Value("${ehr.token.client-auth}")
    private String clientAuth;

    @Value("${ehr.token.username}")
    private String username;

    @Value("${ehr.token.password}")
    private String password;

    @Value("${ehr.token.grant-type}")
    private String grantType;

    private final RestTemplate restTemplate;

    @Override
    public String getAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", clientAuth);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("username", username);
        body.add("password", password);
        body.add("grant_type", grantType);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, request, TokenResponse.class
        );

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return response.getBody().getAccessToken();
        } else {
            throw new RuntimeException("Failed to fetch token. Status: " + response.getStatusCode());
        }
    }
}
