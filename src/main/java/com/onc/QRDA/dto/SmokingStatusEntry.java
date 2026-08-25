package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SmokingStatusEntry {
    private List<GenericItem> currentSmokingStatus;
    private String startDate;
}
