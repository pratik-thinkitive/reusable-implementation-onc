package com.onc.G2.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessRequestResponse {
    private boolean success;
    private String message;
    private String requestId;
    private String status;
}
