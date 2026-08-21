# Knowledge Base — qrdaC1

Generated 2026-08-20. QRDA Category I (CMS139) generation service — Spring Boot 4.1.0 / Java 17 / MDHT CDA.

| Doc | Contents |
|---|---|
| [overview.md](overview.md) | Purpose, tech stack, architecture diagram, module map, document-generation flow, build commands |
| [api-reference.md](api-reference.md) | 8 inbound REST endpoints + 8 outbound Spry Provider API calls, auth model, response codes |
| [data-models.md](data-models.md) | 57 DTOs — response envelopes, core aggregates, terminology models, FHIR mappings, section-key map |
| [patterns.md](patterns.md) | Layering, HTTP client idiom, error handling, CDA construction, template OIDs, code systems, CMS139 measure logic |
| [compliance-map.md](compliance-map.md) | 23 findings — build blockers, HIPAA/PHI exposure, correctness and data-integrity issues, test coverage |

No frontend in this repo — `frontend-map.md` omitted.
No `healthcare-domain-core.md` / `healthcare-domain-reference.md` present; healthcare findings were derived directly from the code.
