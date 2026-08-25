package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FormDataResponse {
    private int code;
    private FormData data;
    private String message;
}
