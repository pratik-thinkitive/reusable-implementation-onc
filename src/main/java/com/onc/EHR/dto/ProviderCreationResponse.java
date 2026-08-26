package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCreationResponse {
    private Integer code;
    private ProviderData data;
    private String message;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderData {
        private Integer id;
        private String username;
        private List<String> roles;
    }
}

