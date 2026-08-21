package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AppointmentData {
    private int total;
    private int pages;
    private int current;
    private int no_of_records;
    private List<Appointment> appointments;
}
