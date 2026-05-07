# MealPrep AI — Design Space

This folder contains design documents for the meal prep application.

## Core Concept
An AI-powered meal planning system built around **three constraint-optimisation loops**:
1. **Preference Loop** — taste, cooking style, cuisine preferences (AI-learned from feedback)
2. **Nutrition Loop** — calorie/macro/micro targets (refined by health tracking outcomes)
3. **Provisions Loop** — pantry, equipment, budget, supplier availability (updated by shopping/usage)

The Meal Planner orchestrates all three simultaneously. Users interact through feedback and manual adjustments while AI handles planning, recipe management, and learning.

## Start Here
- **[system-overview.md](system-overview.md)** — Single source of truth for the architecture

## Design Documents

**Architectural foundation**
- [system-overview.md](system-overview.md) — End-to-end system overview, data models, components, tech stack
- [technical-architecture.md](technical-architecture.md) — Module wiring, service interfaces, events, transactions, DB conventions
- [optimisation-loop.md](optimisation-loop.md) — Shared multi-constraint optimisation pattern reused at week and recipe scales

**Data models (state)**
- [preference-model.md](preference-model.md) — Hard constraints, taste profile, lifestyle config
- [nutrition-model.md](nutrition-model.md) — Macro/micro targets, dietary patterns, intake tracking
- [provision-model.md](provision-model.md) — Pantry, equipment, environment, budget, suppliers

**Orchestration and intelligence**
- [recipe-system.md](recipe-system.md) — Catalogue + adaptation pipeline (recipe-scale loop)
- [meal-planner.md](meal-planner.md) — Week-scale orchestrator (week-scale loop)
- [feedback-system.md](feedback-system.md) — Conversational input, classification, four-destination routing
- [grocery.md](grocery.md) — `GroceryProvider` abstraction, shopping-list calculation, order lifecycle, substitution flow

Anything in [archive/](archive/) is stale and superseded by current design.
