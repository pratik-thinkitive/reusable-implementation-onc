package com.onc.G2.converter;

import com.onc.G2.enums.RequestType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds {@code requestType} query parameters case-insensitively, as the endpoint has always
 * accepted them.
 *
 * <p>Registering this as a converter rather than parsing inside the service is what turns an
 * unknown value into a 400: an {@link IllegalArgumentException} thrown here reaches Spring as a
 * type mismatch, which {@link com.onc.api.GlobalExceptionHandler} reports with the allowed
 * values. Parsed in a service, the same failure surfaced as a 500.
 */
@Component
public class StringToRequestTypeConverter implements Converter<String, RequestType> {

    @Override
    public RequestType convert(String source) {
        return RequestType.valueOf(source.trim().toUpperCase());
    }
}
