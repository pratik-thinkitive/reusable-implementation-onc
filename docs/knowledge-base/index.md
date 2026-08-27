# Knowledge Base — ONC (reusable-implementation-onc)

Updated 2026-08-27. Spring Boot 4.1.0 / Java 17 / PostgreSQL / MDHT CDA. Four modules, named for the certification criteria they serve: **EHR** (shared provider read layer), **C1** (QRDA Category I generation), **C2C3** (Category I import and Category III summary), **G2** (patient electronic access).

| Doc | Contents |
|---|---|
| [overview.md](overview.md) | Purpose, tech stack, architecture diagram, module map, C2C3 and G2 flows, QRDA generation flow, build commands |
| [api-reference.md](api-reference.md) | Response envelope and code table, 33 endpoints across 5 controllers, global error contract, outbound provider calls, configuration |
| [data-models.md](data-models.md) | 2 JPA entities + repository predicates, G2 and C2C3 DTOs, 65 EHR transport DTOs, envelopes, terminology models, FHIR mappings |
| [patterns.md](patterns.md) | Layering, error handling, C2C3's off-pattern conventions, transaction boundaries, date/zone handling, CDA construction, measure definitions, testing |
| [compliance-map.md](compliance-map.md) | 47 findings — deployment blockers, access control and PHI exposure, G2 measure integrity, C1 data integrity, C2C3 gaps, test coverage |
| [CHANGELOG.md](CHANGELOG.md) | Dated record of KB updates |

No frontend in this repo — `frontend-map.md` omitted.
No `healthcare-domain-core.md` / `healthcare-domain-reference.md` present; healthcare findings derived directly from the code.
