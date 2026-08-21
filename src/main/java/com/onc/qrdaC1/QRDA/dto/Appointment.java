package com.onc.qrdaC1.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Appointment {
    private int appointment_id;
    private int patient_id;
    private int clinic_id;
    private String date_time;
    private String end_date_time;
    private String appointment_status;
    private String type;
    private String purpose;
    private boolean is_billable;
    private boolean is_paid;
    private List<AppointmentCategory> category;
    private Map<String, Object> clinic;
    private Map<String, Object> doctor;
    private Map<String, Object> patient;
    private Map<String, Object> service_provider_notes;
    private String created_at;
    private String updated_at;
}
