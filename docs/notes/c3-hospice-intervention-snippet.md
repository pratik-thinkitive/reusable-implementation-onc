# C3 snippet — hospice intervention entries (unattached)

Found as `com/onc/C2/DTOs/Intervention.java` when the C2 folder was added: a fragment of MDHT
CDA **generation** code with no package, no imports and no class declaration, so it could never
compile. It builds Intervention Order / Intervention Performed acts for SNOMED `SCT-238529007`
(hospice care) — generation work, which belongs with C3 rather than the C2 parser it was filed under.

Parked here so it is not lost. It needs a class, imports, and the surrounding `section` and
`parseSnomedCodes` context before it can be used.

```java
implementation to map the SNOMED and ICD codes

        List<FormData> formDataList = getFormDataForPatient(patientData);
        if (!CollectionUtils.isEmpty(formDataList)) {
            for (FormData formData : formDataList) {
                if (Objects.nonNull(formData) && Objects.nonNull(formData.getResponse()) && Objects.nonNull(formData.getResponse().getIntervention())) {
                    Map<String, CodeSection> interventionMap = formData.getResponse().getIntervention();
                    if (!CollectionUtils.isEmpty(interventionMap)) {
                        for (CodeSection codeSection : interventionMap.values()) {
                            if (Objects.nonNull(codeSection) && Objects.nonNull(codeSection.getSnomedCodes())) {
                                List<SnomedCode> snomedCodes = parseSnomedCodes(codeSection.getSnomedCodes());
                                for (SnomedCode snomedCode : snomedCodes) {
                                    if (Objects.nonNull(snomedCode) &&
                                            "SCT-238529007".equals(snomedCode.getConceptId())) { // Check for Hospice Care Code

                                        // --- Start Inlined Intervention Order (Active Status) Logic ---
                                        if ("Active".equalsIgnoreCase(snomedCode.getStatus())) {
                                            Entry hospiceActEntry = CDAFactory.eINSTANCE.createEntry();

                                            Act hospiceAct = CDAFactory.eINSTANCE.createAct();
                                            hospiceAct.setClassCode(x_ActClassDocumentEntryAct.ACT);
                                            hospiceAct.setMoodCode(x_DocumentActMood.RQO); // Requested/Order

                                            // Template IDs for Planned Act and Intervention Order
                                            II plannedActTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            plannedActTemplateId.setRoot("2.16.840.1.113883.10.20.22.4.39");
                                            plannedActTemplateId.setExtension("2014-06-09");
                                            hospiceAct.getTemplateIds().add(plannedActTemplateId);

                                            II interventionOrderTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            interventionOrderTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.31");
                                            interventionOrderTemplateId.setExtension("2021-08-01");
                                            hospiceAct.getTemplateIds().add(interventionOrderTemplateId);

                                            II hospiceActId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceActId.setRoot("1.3.6.1.4.1.115");
                                            UUID hospiceActIdUUID = UUID.randomUUID();
                                            hospiceActId.setExtension(hospiceActIdUUID.toString());
                                            hospiceAct.getIds().add(hospiceActId);

                                            CE hospiceActCode = DatatypesFactory.eINSTANCE.createCE();
                                            hospiceActCode.setCode("385763009");
                                            hospiceActCode.setCodeSystem("2.16.840.1.113883.6.96");
                                            hospiceActCode.setCodeSystemName("SNOMEDCT");
                                            hospiceAct.setCode(hospiceActCode);

                                            ED hospiceActText = DatatypesFactory.eINSTANCE.createED();
                                            hospiceActText.addText("Hospice Care Ambulatory");
                                            hospiceAct.setText(hospiceActText);

                                            CS hospiceActStatus = DatatypesFactory.eINSTANCE.createCS();
                                            hospiceActStatus.setCode("active");
                                            hospiceAct.setStatusCode(hospiceActStatus);

                                            // Set effective time (Low only for active order)
                                            IVL_TS activeEffectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
                                            IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();

                                            if (StringUtils.hasText(snomedCode.getStartDate())) {
                                                try {
                                                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
                                                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMddHHmmss");
                                                    java.util.Date startDate = inputFormat.parse(snomedCode.getStartDate());
                                                    low.setValue(outputFormat.format(startDate));
                                                    activeEffectiveTime.setLow(low);
                                                } catch (Exception e) {
                                                    low.setNullFlavor(NullFlavor.UNK);
                                                    activeEffectiveTime.setLow(low);
                                                }
                                            } else {
                                                low.setNullFlavor(NullFlavor.UNK);
                                                activeEffectiveTime.setLow(low);
                                            }
                                            hospiceAct.setEffectiveTime(activeEffectiveTime);

                                            // Author block
                                            Author hospiceActAuthor = CDAFactory.eINSTANCE.createAuthor();

                                            II hospiceAuthorTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceAuthorTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.155");
                                            hospiceAuthorTemplateId.setExtension("2019-12-01");
                                            hospiceActAuthor.getTemplateIds().add(hospiceAuthorTemplateId);

                                            TS hospiceAuthorTime = DatatypesFactory.eINSTANCE.createTS();
                                            // Placeholder for current timestamp
                                            hospiceAuthorTime.setValue(getCurrentTimestamp());
                                            hospiceActAuthor.setTime(hospiceAuthorTime);

                                            AssignedAuthor hospiceAssignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
                                            II hospiceAssignedAuthorId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceAssignedAuthorId.setNullFlavor(NullFlavor.NA);
                                            hospiceAssignedAuthor.getIds().add(hospiceAssignedAuthorId);

                                            hospiceActAuthor.setAssignedAuthor(hospiceAssignedAuthor);
                                            hospiceAct.getAuthors().add(hospiceActAuthor);

                                            hospiceActEntry.setAct(hospiceAct);
                                            section.getEntries().add(hospiceActEntry);
                                        }
                                        // --- End Inlined Intervention Order Logic ---

                                        // --- Start Inlined Intervention Performed (Completed Status) Logic ---
                                        if ("Completed".equalsIgnoreCase(snomedCode.getStatus())) {
                                            Entry hospiceCompletedEntry = CDAFactory.eINSTANCE.createEntry();

                                            Act hospiceCompletedAct = CDAFactory.eINSTANCE.createAct();
                                            hospiceCompletedAct.setClassCode(x_ActClassDocumentEntryAct.ACT);
                                            hospiceCompletedAct.setMoodCode(x_DocumentActMood.EVN); // Event/Performed

                                            // Template IDs for Procedure Activity and Intervention Performed
                                            II procedureActivityTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            procedureActivityTemplateId.setRoot("2.16.840.1.113883.10.20.22.4.12");
                                            procedureActivityTemplateId.setExtension("2014-06-09");
                                            hospiceCompletedAct.getTemplateIds().add(procedureActivityTemplateId);

                                            II interventionCompletedTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            interventionCompletedTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.32");
                                            interventionCompletedTemplateId.setExtension("2021-08-01");
                                            hospiceCompletedAct.getTemplateIds().add(interventionCompletedTemplateId);

                                            II hospiceCompletedId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceCompletedId.setRoot("1.3.6.1.4.1.115");
                                            UUID hospiceCompletedIdUUID = UUID.randomUUID();
                                            hospiceCompletedId.setExtension(hospiceCompletedIdUUID.toString());
                                            hospiceCompletedAct.getIds().add(hospiceCompletedId);

                                            CE hospiceCompletedCode = DatatypesFactory.eINSTANCE.createCE();
                                            hospiceCompletedCode.setCode("385763009");
                                            hospiceCompletedCode.setCodeSystem("2.16.840.1.113883.6.96");
                                            hospiceCompletedCode.setCodeSystemName("SNOMEDCT");
                                            hospiceCompletedAct.setCode(hospiceCompletedCode);

                                            ED hospiceCompletedText = DatatypesFactory.eINSTANCE.createED();
                                            hospiceCompletedText.addText("Hospice Care Ambulatory");
                                            hospiceCompletedAct.setText(hospiceCompletedText);

                                            CS hospiceCompletedStatus = DatatypesFactory.eINSTANCE.createCS();
                                            hospiceCompletedStatus.setCode("completed");
                                            hospiceCompletedAct.setStatusCode(hospiceCompletedStatus);

                                            // Set effective time (Low and High for completed event)
                                            IVL_TS hospiceCompletedEffectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();

                                            // Low Value (Start Date)
                                            IVXB_TS hospiceCompletedLow = DatatypesFactory.eINSTANCE.createIVXB_TS();
                                            if (StringUtils.hasText(snomedCode.getStartDate())) {
                                                try {
                                                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
                                                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMddHHmmss");
                                                    java.util.Date startDate = inputFormat.parse(snomedCode.getStartDate());
                                                    hospiceCompletedLow.setValue(outputFormat.format(startDate));
                                                } catch (Exception e) {
                                                    hospiceCompletedLow.setNullFlavor(NullFlavor.UNK);
                                                }
                                            } else {
                                                hospiceCompletedLow.setNullFlavor(NullFlavor.UNK);
                                            }
                                            hospiceCompletedEffectiveTime.setLow(hospiceCompletedLow);

                                            // High Value (End Date)
                                            IVXB_TS hospiceCompletedHigh = DatatypesFactory.eINSTANCE.createIVXB_TS();
                                            if (StringUtils.hasText(snomedCode.getEndDate())) {
                                                try {
                                                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
                                                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMddHHmmss");
                                                    java.util.Date endDate = inputFormat.parse(snomedCode.getEndDate());
                                                    hospiceCompletedHigh.setValue(outputFormat.format(endDate));
                                                } catch (Exception e) {
                                                    hospiceCompletedHigh.setNullFlavor(NullFlavor.UNK);
                                                }
                                            } else {
                                                hospiceCompletedHigh.setNullFlavor(NullFlavor.UNK);
                                            }
                                            hospiceCompletedEffectiveTime.setHigh(hospiceCompletedHigh);

                                            hospiceCompletedAct.setEffectiveTime(hospiceCompletedEffectiveTime);

                                            // Author block
                                            Author hospiceCompletedAuthor = CDAFactory.eINSTANCE.createAuthor();

                                            II hospiceCompletedAuthorTemplateId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceCompletedAuthorTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.155");
                                            hospiceCompletedAuthorTemplateId.setExtension("2019-12-01");
                                            hospiceCompletedAuthor.getTemplateIds().add(hospiceCompletedAuthorTemplateId);

                                            TS hospiceCompletedAuthorTime = DatatypesFactory.eINSTANCE.createTS();
                                            // Placeholder for current timestamp
                                            hospiceCompletedAuthorTime.setValue(getCurrentTimestamp());
                                            hospiceCompletedAuthor.setTime(hospiceCompletedAuthorTime);

                                            AssignedAuthor hospiceCompletedAssignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
                                            II hospiceCompletedAssignedAuthorId = DatatypesFactory.eINSTANCE.createII();
                                            hospiceCompletedAssignedAuthorId.setNullFlavor(NullFlavor.NA);
                                            hospiceCompletedAssignedAuthor.getIds().add(hospiceCompletedAssignedAuthorId);

                                            hospiceCompletedAuthor.setAssignedAuthor(hospiceCompletedAssignedAuthor);
                                            hospiceCompletedAct.getAuthors().add(hospiceCompletedAuthor);

                                            hospiceCompletedEntry.setAct(hospiceCompletedAct);
                                            section.getEntries().add(hospiceCompletedEntry);
                                        }
                                        // --- End Inlined Intervention Performed Logic ---
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

```
