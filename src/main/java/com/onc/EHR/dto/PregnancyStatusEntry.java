package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PregnancyStatusEntry {
    private List<GenericItem> values;
}
