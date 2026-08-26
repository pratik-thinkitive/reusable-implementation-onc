package com.onc.EHR.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCreationRequest {
    @JsonProperty("user_details")
    private UserDetails userDetails;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserDetails {
        @JsonProperty("first_name")
        private String firstName;
        
        @JsonProperty("middle_name")
        private String middleName;
        
        @JsonProperty("last_name")
        private String lastName;
        
        private String username;
        
        @JsonProperty("clinic_id_list")
        private List<Integer> clinicIdList;
        
        private List<String> roles;
        
        private String mobile;
        
        private String search;
        
        private String email;
    }
}

