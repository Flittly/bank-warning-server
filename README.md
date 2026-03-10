# Yangtze Bank-Collapse System Backend

`bank-warning-server` now serves the original business-facing `/v0/bank/*` API surface.

## Responsibilities

- expose legacy-compatible business APIs such as `/v0/bank/tasks`, `/v0/bank/sections`, `/v0/bank/results`;
- operate directly on the existing PostgreSQL/PostGIS schema without changing table structure;
- run task workflow by reading section parameters from `cross_sections`, calling Python `/v0/mi/risk-level`, polling `/v0/mc/*`, and persisting into `bank_risk_results`;
- keep Python focused on model execution while Java handles CRUD and workflow orchestration.

## Key compatibility points

- request/response field names follow the original snake_case contract from the project docs;
- section parameters remain independent copies stored on `cross_sections`;
- `basic_params` is still only a template source;
- `POST /v0/bank/tasks/{task_id}/run` keeps the documented meaning.

## Run

```bash
mvn spring-boot:run
```

Defaults:

- Java service: `http://localhost:8090`
- Python model service target: `http://localhost:8088`
- database: `jdbc:postgresql://localhost:5432/bank_risk_db`
