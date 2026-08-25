package com.onc.QRDA.service.impl;

import com.onc.QRDA.dto.TokenResponse;
import com.onc.QRDA.service.QRDATokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class QRDATokenServiceImpl implements QRDATokenService {

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

    public String getAccessToken() {
        RestTemplate restTemplate = new RestTemplate();

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

