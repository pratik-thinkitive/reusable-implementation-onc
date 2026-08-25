package com.onc.EHR.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LoincCodesDeserializer extends JsonDeserializer<List<LoincCode>> {

    @Override
    public List<LoincCode> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = p.getCodec();
        JsonNode node = codec.readTree(p);

        List<LoincCode> result = new ArrayList<>();

        if (node.isArray()) {
            for (JsonNode item : node) {
                result.add(codec.treeToValue(item, LoincCode.class));
            }
        } else if (node.isTextual()) {
            LoincCode loincCode = new LoincCode();
            loincCode.setCode(node.asText());
            result.add(loincCode);
        }

        return result;
    }
}

