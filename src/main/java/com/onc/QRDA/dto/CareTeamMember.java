package com.onc.QRDA.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareTeamMember {
    @JsonProperty("member_name")
    private String memberName;

    @JsonProperty("member_identifier")
    private String memberIdentifier;

    @JsonProperty("member_role")
    private String memberRole;

    @JsonProperty("member_location")
    private String memberLocation;

    @JsonProperty("member_telecom")
    private String memberTelecom;
}

