# Knowledge Base — ONC (reusable-implementation-onc)

Updated 2026-08-25. Spring Boot 4.1.0 / Java 17 / PostgreSQL / MDHT CDA. Three modules: **EHR** (shared provider read layer), **QRDA** (CMS139 Cat I generation), **G2** (patient electronic access measure).

| Doc | Contents |
|---|---|
| [overview.md](overview.md) | Purpose, tech stack, architecture diagram, module map, G2 access flow, QRDA generation flow, build commands |
| [api-reference.md](api-reference.md) | Response envelope and code table, 31 inbound endpoints across 3 controllers, global error contract, outbound provider calls, configuration |
| [data-models.md](data-models.md) | 2 JPA entities + repository predicates + 8 G2 DTOs; 62 EHR transport DTOs, envelopes, terminology models, FHIR mappings |
| [patterns.md](patterns.md) | Layering, per-module error handling, transaction boundaries, date/zone conventions, CDA construction, measure definitions, testing |
| [compliance-map.md](compliance-map.md) | 38 findings — deployment blockers, access control & PHI exposure, G2 measure integrity, QRDA data integrity, test coverage |
| [CHANGELOG.md](CHANGELOG.md) | Dated record of KB updates |

No frontend in this repo — `frontend-map.md` omitted.
No `healthcare-domain-core.md` / `healthcare-domain-reference.md` present; healthcare findings derived directly from the code.
