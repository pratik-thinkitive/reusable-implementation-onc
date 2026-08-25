package com.onc.QRDA.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ImplantableDeviceEntry {
    private String deviceLookup;
    private String primaryId;
    private String brandName;
    private String companyName;
    private String modelNo;
    private String description;
    private String implantLocation;
    private String dateOfImplant;
    private String udi;
    private String serialNumber;
    private String lotNumber;
    private String manufacturingDate;
    private String expirationDate;
    private String deviceType;
    private String isActive;
}
