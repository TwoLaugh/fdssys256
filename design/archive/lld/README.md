# Low-Level Design

## Documents

1. **data-model.md** — Full database schema, entity relationships, constraints, indexes
2. **api-contracts.md** — REST API endpoints, request/response shapes, status codes
3. **service-interfaces.md** — Internal module contracts, what each service exposes
4. **key-flows.md** — Sequence diagrams for critical user journeys
5. **frontend-architecture.md** — Component tree, state management, routing

## Conventions
- All timestamps stored as UTC
- All monetary values stored as pence (integer) to avoid floating point
- All weights stored in grams (integer) for consistency
- JSON responses use camelCase
- Database columns use snake_case
- Module packages: `com.example.mealprep.<module>`
