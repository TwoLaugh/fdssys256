# Phased Delivery

*Extracted from system-overview.md. See that doc for the architecture these phases implement.*

## Phase 1: Core Loop
- Auth (simple username/password, multi-user)
- Preference Model (initial setup, hard/soft constraints, cooking prefs)
- Nutrition Model (calorie/macro targets)
- Provisions (pantry — manual tracking, equipment list)
- Recipe Engine (CRUD, import from URL, AI generation, user + system catalogues, versioning + branching)
- Recipe Optimiser (adapt recipes on import, re-optimise on data model change)
- Meal Planner (two-phase optimisation: plan composition + creative augmentation; invokes Recipe Optimiser as pre-step)
- Grocery integration via GroceryProvider abstraction (Tesco as first implementation: price checking + shopping list + ordering)
- Basic nutrition dashboard
- React frontend with core views

## Phase 2: Intelligence
- Feedback system (conversational, context-aware routing to four data destinations: preference, nutrition, provisions, recipe engine)
- Preference Model evolution (AI-maintained, regenerated from feedback)
- Recipe Optimiser post-feedback trigger (feedback → proposed recipe changes)
- Recipe discovery (online search + filter)
- Mid-week re-optimisation (data model changes from disruptions, grocery substitutions, or macro corrections trigger re-plan of remaining days)
- Nutrition tracking (planned vs actual)
- Health tracking tier 1 (mood, symptoms, weight — feeds into nutrition model)

## Phase 3: Health & Polish
- Weekly/monthly AI reviews (health → nutrition model refinement)
- Progress photos
- Notifications (expiry, prep reminders, defrost)
- Food waste tracking and reporting

## Phase 4: Advanced Health & Expansion
- Wearable integration (Apple Health, Garmin, etc.)
- Blood panel upload and AI analysis
- Genomics integration
- Household multi-user (shared provisions, shared meal slots)
- PWA (installable, offline recipe viewing)
- Data backup and export
- Natural language search across the system
