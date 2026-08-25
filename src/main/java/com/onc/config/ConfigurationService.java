package com.onc.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ConfigurationService {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Multi-valued demographics (race, ethnicity, preferred language) are declared
                // as lists. Providers vary in whether they send a bare string or an array for
                // a single value, so accept both rather than failing on the scalar form.
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

}
