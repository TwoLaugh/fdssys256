# API Contracts

Base URL: `/api/v1`

All responses wrapped in a standard envelope for errors:
```json
// Success: returns the data directly (no wrapper)
// Error:
{
  "error": "VALIDATION_ERROR",
  "message": "Calorie target must be positive",
  "timestamp": "2026-03-20T12:00:00Z"
}
```

---

## Profile — `/api/v1/profile`

### GET /profile
Returns the current user profile with all related data.

```json
// Response 200
{
  "id": 1,
  "name": "Irene",
  "dietaryIdentity": "omnivore",
  "calorieTargetMin": 2000,
  "calorieTargetMax": 2200,
  "proteinTargetG": 150,
  "carbTargetG": 200,
  "fatTargetG": 70,
  "goalContext": "maintenance",
  "skillLevel": "intermediate",
  "weeknightMaxMins": 30,
  "weekendMaxMins": 120,
  "batchCookWilling": true,
  "weeklyBudgetPence": 7000,
  "priceSensitivity": "mid_range",
  "organicPreference": false,
  "plansBreakfast": true,
  "plansLunch": true,
  "plansDinner": true,
  "plansSnacks": false,
  "newRecipesPerWeek": 3,
  "adventurousness": "moderate",
  "primaryStore": "tesco",
  "shoppingFrequency": "weekly",
  "deliveryPreference": "delivery",
  "allergies": ["nuts"],
  "intolerances": [
    {"substance": "lactose", "severity": "moderate", "notes": "hard cheese is fine"}
  ],
  "ingredientDislikes": ["coriander", "blue cheese"],
  "cuisinePreferences": [
    {"cuisine": "mediterranean", "preference": "favourite"},
    {"cuisine": "east_asian", "preference": "favourite"}
  ],
  "equipment": ["oven", "hob", "air_fryer", "blender"],
  "fixedMealSlots": [
    {"dayOfWeek": null, "mealType": "breakfast", "description": "overnight oats", "appliesTo": "weekday"}
  ],
  "eatingOutSchedule": [
    {"dayOfWeek": "friday", "mealType": "dinner", "notes": "usually eat out"}
  ]
}
```

### PUT /profile
Update the profile. Accepts partial updates (only send fields to change).

```json
// Request
{
  "calorieTargetMin": 2100,
  "calorieTargetMax": 2300,
  "allergies": ["nuts", "sesame"]
}

// Response 200: full profile (same shape as GET)
```

---

## Recipes — `/api/v1/recipes`

### GET /recipes
List recipes with filters.

Query params:
- `source` — filter by source: `ai_generated`, `user_submitted`, `imported`
- `cuisine` — filter by cuisine
- `mealType` — filter by meal type: `breakfast`, `lunch`, `dinner`, `snack`
- `tags` — comma-separated tag filter
- `minRating` — minimum average rating (1-5)
- `archived` — include archived (default: false)
- `search` — text search on name/ingredients
- `sort` — `rating`, `recent`, `times_cooked`, `name` (default: `recent`)
- `page`, `size` — pagination (default: page=0, size=20)

```json
// Response 200
{
  "content": [
    {
      "id": 1,
      "name": "Chicken Stir Fry",
      "source": "ai_generated",
      "currentVersion": 2,
      "avgRating": 4.5,
      "timesCooked": 3,
      "lastCookedAt": "2026-04-01T18:30:00Z",
      "mealTypes": ["dinner"],
      "cuisine": "east_asian",
      "difficulty": "easy",
      "tags": ["quick", "high-protein"],
      "calories": 450,
      "proteinG": 38,
      "prepTimeMins": 10,
      "cookTimeMins": 15
    }
  ],
  "totalElements": 45,
  "totalPages": 3,
  "page": 0
}
```

### GET /recipes/{id}
Full recipe detail including current version.

```json
// Response 200
{
  "id": 1,
  "name": "Chicken Stir Fry",
  "source": "ai_generated",
  "sourceUrl": null,
  "currentVersion": 2,
  "avgRating": 4.5,
  "timesCooked": 3,
  "mealTypes": ["dinner"],
  "cuisine": "east_asian",
  "difficulty": "easy",
  "tags": ["quick", "high-protein"],
  "archived": false,
  "notes": [
    {"id": 1, "note": "Use more chilli than recipe says", "source": "user"}
  ],
  "version": {
    "versionNumber": 2,
    "servings": 2,
    "prepTimeMins": 10,
    "cookTimeMins": 15,
    "ingredients": [
      {
        "id": 1,
        "originalText": "500g chicken thighs, diced",
        "ingredientName": "chicken thigh, raw",
        "quantity": 500,
        "unit": "g",
        "gramsEstimate": 500,
        "category": "protein"
      }
    ],
    "steps": [
      "Dice the chicken thighs into 2cm pieces.",
      "Heat oil in a wok over high heat.",
      "..."
    ],
    "calories": 450,
    "proteinG": 38.0,
    "carbsG": 25.0,
    "fatG": 18.0,
    "fibreG": 4.0,
    "changelog": "Reduced honey to 1tbsp, added rice vinegar for balance",
    "aiNotes": "Sweetness issue resolved. User responded well to acidity."
  }
}
```

### GET /recipes/{id}/versions
All versions of a recipe.

```json
// Response 200
[
  {
    "versionNumber": 2,
    "changelog": "Reduced honey to 1tbsp, added rice vinegar",
    "calories": 450,
    "avgRating": 4.5,
    "feedbackCount": 2,
    "createdAt": "2026-04-08T12:00:00Z"
  },
  {
    "versionNumber": 1,
    "changelog": null,
    "calories": 470,
    "avgRating": 3.0,
    "feedbackCount": 1,
    "createdAt": "2026-03-25T12:00:00Z"
  }
]
```

### POST /recipes
Create a new recipe manually.

```json
// Request
{
  "name": "My Favourite Pasta",
  "mealTypes": ["dinner"],
  "cuisine": "italian",
  "difficulty": "easy",
  "tags": ["quick"],
  "servings": 2,
  "prepTimeMins": 5,
  "cookTimeMins": 15,
  "ingredients": [
    "200g penne pasta",
    "2 cloves garlic, minced",
    "1 tin chopped tomatoes",
    "Handful of fresh basil"
  ],
  "steps": [
    "Cook pasta according to packet instructions.",
    "Fry garlic in olive oil for 1 minute.",
    "Add tomatoes, simmer 10 minutes.",
    "Toss with pasta and basil."
  ]
}

// Response 201: full recipe object (nutrition auto-calculated)
```

### POST /recipes/import
Import a recipe from a URL.

```json
// Request
{
  "url": "https://www.bbcgoodfood.com/recipes/chicken-stir-fry"
}

// Response 201: full recipe object (AI-extracted and nutrition calculated)
```

### POST /recipes/{id}/suggest-changes
Chat with AI about a specific recipe.

```json
// Request
{
  "message": "Can you make this lower carb? I want to keep the flavour though."
}

// Response 200
{
  "response": "I'd suggest replacing the rice with cauliflower rice and reducing the honey in the sauce. This should bring carbs from 45g to about 15g per serving while keeping the umami flavour profile. Want me to create a new version with these changes?",
  "proposedChanges": {
    "ingredientChanges": [
      {"from": "200g jasmine rice", "to": "300g cauliflower rice"},
      {"from": "2 tbsp honey", "to": "1 tsp honey"}
    ],
    "estimatedNutrition": {"calories": 380, "proteinG": 38, "carbsG": 15, "fatG": 18}
  }
}
```

### POST /recipes/{id}/apply-changes
Confirm suggested changes → creates new version.

```json
// Request
{
  "changelog": "Swapped rice for cauliflower rice, reduced honey — lower carb version"
}

// Response 201: full recipe object with new version
```

### POST /recipes/{id}/notes
Add a recipe-specific note.

```json
// Request
{
  "note": "Marinate the chicken for at least 1 hour, don't skip this"
}

// Response 201
```

### PUT /recipes/{id}/archive
Archive a recipe (soft delete).
```
// Response 204
```

---

## Pantry — `/api/v1/pantry`

### GET /pantry
List all pantry items.

Query params:
- `storage` — `fridge`, `freezer`, `cupboard`
- `category` — filter by category
- `expiringSoon` — boolean, items expiring within 3 days
- `sort` — `expiry`, `name`, `category` (default: `category`)

```json
// Response 200
[
  {
    "id": 1,
    "name": "Chicken breast",
    "quantity": 500,
    "unit": "g",
    "category": "protein",
    "storage": "fridge",
    "expiryDate": "2026-03-22",
    "opened": false,
    "addedFrom": "shopping_list",
    "addedAt": "2026-03-20T10:00:00Z",
    "daysUntilExpiry": 2
  }
]
```

### POST /pantry
Add a pantry item.

```json
// Request
{
  "name": "Chicken breast",
  "quantity": 500,
  "unit": "g",
  "category": "protein",
  "storage": "fridge",
  "expiryDate": "2026-03-22"
}

// Response 201
```

### PUT /pantry/{id}
Update a pantry item (e.g., adjust quantity after partial use).

### DELETE /pantry/{id}
Remove a pantry item.

### POST /pantry/{id}/waste
Log a wasted pantry item.

```json
// Request
{
  "reason": "expired",
  "notes": "Forgot about it at the back of the fridge"
}

// Response 200 (item removed from pantry, waste logged)
```

---

## Meal Plan — `/api/v1/plans`

### POST /plans/generate
Generate a new weekly meal plan.

```json
// Request
{
  "weekStartDate": "2026-03-23",
  "overrides": [
    {"date": "2026-03-25", "mealType": "dinner", "note": "I want tacos"},
    {"date": "2026-03-28", "mealType": "dinner", "note": "eating out"}
  ]
}

// Response 201
{
  "id": 1,
  "weekStartDate": "2026-03-23",
  "status": "active",
  "estimatedCostPence": 4500,
  "slots": [
    {
      "id": 1,
      "date": "2026-03-23",
      "mealType": "breakfast",
      "recipe": { "id": 5, "name": "Overnight Oats", "calories": 350 },
      "servings": 1,
      "status": "planned",
      "aiNotes": "Fixed breakfast slot"
    },
    {
      "id": 2,
      "date": "2026-03-23",
      "mealType": "lunch",
      "recipe": { "id": 12, "name": "Chicken & Rice Bowl", "calories": 550 },
      "servings": 1,
      "status": "planned",
      "aiNotes": "Uses chicken breast expiring Tuesday"
    }
  ],
  "ingredientFlow": [
    {
      "ingredientName": "chicken breast",
      "totalNeededG": 680,
      "fromPantryG": 500,
      "toPurchaseG": 180,
      "allocations": [
        {"mealSlotId": 2, "grams": 200, "date": "2026-03-23"},
        {"mealSlotId": 8, "grams": 250, "date": "2026-03-25"},
        {"mealSlotId": 14, "grams": 230, "date": "2026-03-27"}
      ]
    }
  ],
  "dailyNutrition": [
    {
      "date": "2026-03-23",
      "calories": 2150,
      "proteinG": 145,
      "carbsG": 210,
      "fatG": 68
    }
  ]
}
```

### GET /plans/current
Get the current active plan.

### GET /plans/{id}
Get a specific plan by ID.

### PUT /plans/{id}/slots/{slotId}/status
Update a meal slot status.

```json
// Request
{
  "status": "cooked"  // or "skipped"
}

// Response 200 (also triggers pantry deduction if "cooked")
```

### POST /plans/{id}/slots/{slotId}/skip
Skip a meal with intent.

```json
// Request
{
  "intent": "no_change"  // or "move_to_another_day", "adjust_week"
}

// Response 200 (if "adjust_week", returns updated plan)
```

### POST /plans/{id}/slots/{slotId}/swap
Swap a meal for a different recipe.

```json
// Request
{
  "recipeId": 15  // or null to let AI suggest
}

// Response 200 (updated plan with new meal + recalculated nutrition/shopping)
```

---

## Shopping — `/api/v1/shopping`

### POST /shopping/generate
Generate shopping list from the current plan.

```json
// Response 201
{
  "id": 1,
  "mealPlanId": 1,
  "status": "pending",
  "estimatedCostPence": 3200,
  "items": [
    {
      "id": 1,
      "name": "Chicken breast",
      "quantity": 180,
      "unit": "g",
      "category": "protein",
      "checked": false,
      "estimatedPricePence": 350
    }
  ]
}
```

### GET /shopping/current
Get the current shopping list.

### PUT /shopping/{id}/items/{itemId}/check
Toggle an item as checked.

### POST /shopping/{id}/complete
Mark shopping as complete. Adds unchecked items to pantry.

```json
// Request
{
  "addToPantry": true
}

// Response 200 (pantry updated with purchased items)
```

### POST /shopping/{id}/order-tesco
Trigger Tesco ordering automation.

```json
// Response 202 (accepted — async operation)
{
  "jobId": "abc-123",
  "status": "in_progress",
  "message": "Adding items to your Tesco basket. You'll review before checkout."
}
```

---

## Feedback — `/api/v1/feedback`

### POST /feedback
Submit feedback for a meal.

```json
// Request
{
  "mealSlotId": 2,
  "feedback": "The chicken was great but the sauce was way too sweet. Portions were fine. Would eat again if the sauce was fixed."
}

// Response 201
{
  "id": 1,
  "mealSlotId": 2,
  "recipeVersionId": 3,
  "rawFeedback": "The chicken was great but...",
  "tasteScore": 3.5,
  "easeScore": 4.0,
  "portionAssessment": "right",
  "repeatDesire": "yes",
  "aiInterpretation": "User liked the protein and cooking method but found the sauce too sweet. Likely the honey/soy ratio.",
  "aiSuggestedChanges": "Reduce honey from 2tbsp to 1tbsp, add 1tsp rice vinegar for balance."
}
```

### GET /feedback?recipeId={id}
Get all feedback for a recipe.

### GET /preference-model
Get the current preference model.

```json
// Response 200: the full preference model JSON (as designed in preference-model.md)
```

### POST /preference-model/regenerate
Force-regenerate the preference model from all feedback.

---

## Nutrition — `/api/v1/nutrition`

### GET /nutrition/daily/{date}
Get nutrition for a specific day.

```json
// Response 200
{
  "date": "2026-03-23",
  "target": {"calories": 2100, "proteinG": 150, "carbsG": 200, "fatG": 70},
  "actual": {"calories": 1850, "proteinG": 135, "carbsG": 180, "fatG": 62},
  "meals": [
    {
      "mealType": "breakfast",
      "status": "as_planned",
      "recipeName": "Overnight Oats",
      "calories": 350,
      "proteinG": 15
    },
    {
      "mealType": "lunch",
      "status": "as_planned",
      "recipeName": "Chicken & Rice Bowl",
      "calories": 550,
      "proteinG": 42
    },
    {
      "mealType": "dinner",
      "status": "skipped",
      "recipeName": "Salmon Stir Fry",
      "calories": 0,
      "proteinG": 0
    }
  ]
}
```

### GET /nutrition/weekly/{weekStartDate}
Weekly nutrition summary with daily breakdowns.

### PUT /nutrition/log/{date}/{mealType}
Update what was actually eaten.

```json
// Request
{
  "status": "modified",
  "actualServings": 1.5,
  "notes": "Had an extra half portion"
}

// Response 200
```

---

## Health — `/api/v1/health`

### POST /health/log
Log a health check-in.

```json
// Request
{
  "date": "2026-03-23",
  "timeOfDay": "evening",
  "moodScore": 4,
  "energyScore": 3,
  "sleepQuality": 4,
  "weightKg": 75.5,
  "symptoms": ["mild_bloating"],
  "notes": "Felt a bit bloated after lunch"
}

// Response 201
```

### GET /health/log?from={date}&to={date}
Get health logs for a date range.

### GET /health/review/weekly/{weekStartDate}
Get or generate the weekly AI review.

### GET /health/review/monthly/{month}
Get or generate the monthly AI review.

---

## Discovery — `/api/v1/discovery`

### POST /discovery/search
Trigger recipe discovery.

```json
// Request
{
  "focus": "quick weeknight dinners",    // optional — specific request
  "count": 5                              // how many suggestions
}

// Response 200
{
  "recipes": [
    {
      "id": 1,
      "name": "15-Minute Miso Salmon",
      "sourceUrl": "https://...",
      "summary": "Quick pan-fried salmon with miso glaze...",
      "estimatedCalories": 420,
      "estimatedPrepMins": 5,
      "cuisine": "japanese",
      "fitScore": 0.92,
      "fitReasoning": "Matches high-protein goal, quick prep, user has shown preference for umami flavours"
    }
  ]
}
```

### POST /discovery/{id}/accept
Accept a discovered recipe into the library.

### POST /discovery/{id}/reject
Reject a suggestion (influences future discovery).

---

## Notifications — `/api/v1/notifications`

### GET /notifications
Get unread/recent notifications.

### PUT /notifications/{id}/read
Mark as read.

### PUT /notifications/{id}/dismiss
Dismiss notification.

---

## AI Cost — `/api/v1/ai/usage`

### GET /ai/usage?month={YYYY-MM}
Get AI usage and cost for a month.

```json
// Response 200
{
  "month": "2026-03",
  "totalCostPence": 350,
  "totalCalls": 42,
  "breakdown": [
    {"taskType": "plan_assembly", "calls": 4, "costPence": 120},
    {"taskType": "recipe_import", "calls": 8, "costPence": 45},
    {"taskType": "feedback_interpret", "calls": 15, "costPence": 30}
  ]
}
```
