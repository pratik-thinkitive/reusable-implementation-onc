package com.onc.EHR.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/* Reusable item type */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GenericItem {
    private String itemName;
    private String id;
    private String valueRefId;
    private String name;
    private List<AssociatedField> associatedFields;
}
