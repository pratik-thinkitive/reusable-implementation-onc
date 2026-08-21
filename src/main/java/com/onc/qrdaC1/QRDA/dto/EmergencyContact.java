package com.onc.qrdaC1.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyContact {
    @JsonProperty("emergency_contact|type")
    private String type;

    @JsonProperty("emergency_contact|name")
    private String name;

    @JsonProperty("emergency_contact|relationship_to_patient__")
    private String relationship;

    @JsonProperty("emergency_contact|phone")
    private String phone;
}
