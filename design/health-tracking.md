# Health Tracking & Direction System

## The Big Picture

The meal planner becomes much more powerful if it can close the loop between **what you eat** and **how it affects you**. Right now the system plans meals and tracks nutrition. But nutrition is a means to an end — the actual goals are things like energy, body composition, mood, symptom management, performance.

This is essentially an expansion of the user profile from static preferences into a **living, data-informed health model**.

## Data Sources (layered, add over time)

### Tier 1: Self-Reported (v1-ready)
- **Mood/energy log**: simple daily check-in (1-5 scale + optional note), or a few times a day
- **Symptom tracker**: headaches, bloating, fatigue, brain fog, skin issues, digestive symptoms
  - Especially valuable for people on elimination diets (low histamine, FODMAP)
  - Correlate symptoms with specific ingredients/meals over time
- **Weight tracker**: manual daily/weekly weigh-ins, trend line (not just single readings)
- **Progress photos**: periodic photos stored privately, shown as a timeline
- **Sleep quality**: manual rating if no wearable

### Tier 2: Wearables & Devices
- **Smartwatch/fitness tracker**: Apple Health, Google Fit, Garmin, Whoop, Oura
  - Sleep data (duration, quality, HRV)
  - Activity data (steps, exercise, calories burned)
  - Resting heart rate trends
  - Stress/recovery scores
- **Smart scales**: weight + body composition (body fat %, muscle mass)
- **CGM (continuous glucose monitor)**: glucose response to meals — extremely powerful for personalised nutrition, becoming consumer-available (Zoe, Levels)

### Tier 3: Lab Work
- **Blood panels**: user uploads results periodically
  - Standard: cholesterol, blood glucose, HbA1c, iron, vitamin D, B12, thyroid
  - AI flags anything out of range and suggests dietary adjustments
  - AI suggests which tests to get next based on goals and symptoms
  - "Your vitamin D is low — I'll prioritise recipes with oily fish and consider supplementation"
- **Other tests**: food sensitivity panels, microbiome tests
  - These are controversial in terms of scientific validity but some users value them
  - System can incorporate results without claiming medical authority

### Tier 4: Genomics (speculative, future)
- **Full genome data** (23andMe, AncestryDNA raw data, or whole genome sequencing)
- Known food-related genetic variants:
  - MTHFR — folate metabolism
  - CYP1A2 — caffeine metabolism (fast/slow metaboliser)
  - MCM6/LCT — lactose tolerance
  - HLA-DQ2/DQ8 — coeliac predisposition
  - FTO — appetite regulation, obesity risk
  - FADS1/FADS2 — omega-3/6 fatty acid metabolism
  - ALDH2 — alcohol metabolism
  - Many more being discovered
- AI extracts relevant SNPs from raw genome data
- Generates dietary considerations: "You're likely a slow caffeine metaboliser — limit coffee after noon"
- **Important**: frame as "considerations" not diagnoses. Genetics suggests predisposition, not certainty.

---

## How This Feeds Back Into the System

### Short-term feedback loop (daily/weekly)
```
Meal eaten → Mood/energy/symptoms logged → AI notices patterns
  → "You've reported bloating after 3 of the last 4 meals containing chickpeas"
  → Suggests: reduce chickpea frequency, try different preparation (soaking longer), or test intolerance
  → Updates user profile soft constraints
```

### Medium-term direction (monthly)
```
Weight trend + nutrition data + activity → AI assesses progress toward goals
  → "You've lost 1.5kg this month on 2100cal/day. Protein intake averaging 130g vs 150g target."
  → Suggests: adjust calorie target, increase protein in upcoming plans
  → Or: "Weight stable, but energy scores are up 20% — current approach seems to be working, maintain"
```

### Long-term optimisation (quarterly+)
```
Blood panel results + symptom trends + genome data + cumulative feedback
  → "Your iron has improved since we added more red meat and vitamin C pairing 3 months ago"
  → "Suggesting a follow-up blood test in 6 weeks to confirm trend"
  → Updates nutrition priorities in the meal planner
```

---

## Weekly/Monthly Review

An AI-generated report that synthesises everything:

### Weekly Review
- Meals: planned vs actual, what was skipped/swapped
- Nutrition: average daily macros vs targets, trends
- Mood/energy: average scores, any notable patterns
- Symptoms: any recurring, any new
- Weight: current vs trend
- Food waste: what was thrown away, cost
- Budget: spent vs target
- AI recommendations for next week

### Monthly Review
- Everything above but trends over 4 weeks
- Progress toward goals (weight, body composition, energy, symptom reduction)
- Recipe evolution: which recipes improved, which were retired
- Preference model update: what the AI has learned about your tastes
- Suggested profile adjustments: "Based on this month, I'd suggest increasing your calorie target by 100"
- Suggested tests: "It's been 3 months since your last blood panel — consider retesting iron and vitamin D"

---

## Architecture Considerations

This is conceptually an **expansion of User Profile + Nutrition Tracker**, not a separate system.

```
User Profile (static)          Health Data (dynamic)
├── dietary identity            ├── mood/energy logs
├── allergies                   ├── symptom logs
├── goals                      ├── weight history
├── preferences                ├── progress photos
└── cooking prefs              ├── wearable data (synced)
                               ├── blood panel results
                               ├── genome-derived considerations
                               └── AI-generated health insights

Both feed into → Meal Planner as context
```

The key is that health data is **time-series** while profile data is **mostly static**. Different storage and query patterns, but they inform the same planning decisions.

---

## Important Guardrails

- **Not medical advice**: system should never diagnose or prescribe. Frame everything as "considerations" and "suggestions"
- **Privacy**: health data is extremely sensitive. Genome data especially. Local storage, encryption, no third-party sharing
- **User control**: user can ignore any suggestion. System doesn't nag.
- **Scientific rigour**: don't overstate correlations. "You reported bloating after chickpeas 3 times" is data. "Chickpeas cause you bloating" is a claim that needs more evidence.
- **Gradual rollout**: start with simple mood/weight tracking, add complexity as the system proves its value
