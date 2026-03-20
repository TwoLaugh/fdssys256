# Service Interfaces

Each module exposes a Java service interface. Controllers are thin — they delegate to services. Modules interact through these interfaces, never by accessing each other's repositories directly.

---

## ProfileService

```java
public interface ProfileService {
    UserProfileDto getProfile();
    UserProfileDto updateProfile(UpdateProfileRequest request);

    // Convenience methods for other modules
    List<String> getAllergens();               // hard constraints
    List<String> getDislikedIngredients();     // soft constraints
    String getDietaryIdentity();
    NutritionTargets getNutritionTargets();    // calorie range + macro targets
    CookingPreferences getCookingPreferences();
}
```

Used by: everything. This is the most depended-on service.

---

## RecipeService

```java
public interface RecipeService {
    // CRUD
    RecipeDetailDto getRecipe(Long id);
    Page<RecipeSummaryDto> listRecipes(RecipeFilterRequest filter, Pageable pageable);
    RecipeDetailDto createRecipe(CreateRecipeRequest request);
    RecipeDetailDto importFromUrl(String url);
    void archiveRecipe(Long id);

    // Versioning
    List<RecipeVersionSummaryDto> getVersionHistory(Long recipeId);
    RecipeDetailDto createNewVersion(Long recipeId, CreateVersionRequest request);

    // Notes
    void addNote(Long recipeId, String note, String source);

    // AI interaction
    RecipeSuggestionDto suggestChanges(Long recipeId, String userMessage);
    RecipeDetailDto applyChanges(Long recipeId, String changelog);

    // For planner context assembly
    List<RecipeIndexEntry> getRecipeIndex();  // lightweight: id, name, tags, rating, macros
    RecipeDetailDto getRecipeVersion(Long recipeId, int versionNumber);

    // Stats
    void incrementTimesCooked(Long recipeId);
    void updateAvgRating(Long recipeId);
}
```

### RecipeIndexEntry (lightweight DTO for planner pass 1)
```java
public record RecipeIndexEntry(
    Long id,
    String name,
    String[] mealTypes,
    String cuisine,
    String difficulty,
    String[] tags,
    double avgRating,
    int timesCooked,
    LocalDateTime lastCookedAt,
    int calories,
    double proteinG,
    int prepTimeMins,
    int cookTimeMins
) {}
```

---

## NutritionEngine

```java
public interface NutritionEngine {
    /**
     * Parse ingredients, map to USDA, calculate nutrition.
     * Called when a recipe is created or a new version is made.
     */
    RecipeNutrition calculateForRecipe(List<String> rawIngredients, int servings);

    /**
     * Map a single ingredient to the nutrition database.
     * Returns cached result if available.
     */
    IngredientMappingDto mapIngredient(String ingredientText);

    /**
     * Aggregate nutrition for a day's meals.
     */
    DailyNutrition calculateDailyNutrition(List<MealSlotDto> meals);
}

public record RecipeNutrition(
    int caloriesPerServing,
    double proteinG,
    double carbsG,
    double fatG,
    double fibreG,
    JsonNode fullNutrition,        // all micros
    List<ParsedIngredient> parsedIngredients
) {}
```

---

## PantryService

```java
public interface PantryService {
    List<PantryItemDto> listItems(PantryFilterRequest filter);
    PantryItemDto addItem(CreatePantryItemRequest request);
    PantryItemDto updateItem(Long id, UpdatePantryItemRequest request);
    void removeItem(Long id);

    // Waste tracking
    void logWaste(Long itemId, String reason, String notes);
    List<WasteLogDto> getWasteLog(LocalDate from, LocalDate to);

    // Used by planner
    List<PantryItemDto> getItemsExpiringBefore(LocalDate date);
    List<PantryItemDto> getAvailableItems();   // everything in stock

    // Used by meal completion
    void deductIngredients(List<RecipeIngredientDto> ingredients);

    // Used by shopping completion
    void addFromShoppingList(List<ShoppingItemDto> purchasedItems);
}
```

---

## PlannerService

```java
public interface PlannerService {
    /**
     * Generate a new weekly meal plan.
     * This is the big AI call (two-pass).
     */
    MealPlanDto generatePlan(GeneratePlanRequest request);

    MealPlanDto getCurrentPlan();
    MealPlanDto getPlan(Long id);

    // Slot actions
    MealSlotDto updateSlotStatus(Long planId, Long slotId, String status);
    MealPlanDto skipMeal(Long planId, Long slotId, SkipIntent intent);
    MealPlanDto swapMeal(Long planId, Long slotId, Long newRecipeId);  // null = AI suggests

    /**
     * Mark a meal as cooked.
     * Triggers: pantry deduction, recipe stats update, nutrition log entry.
     */
    MealSlotDto markAsCooked(Long planId, Long slotId);
}

public record GeneratePlanRequest(
    LocalDate weekStartDate,
    List<MealOverride> overrides
) {}

public record MealOverride(
    LocalDate date,
    String mealType,
    String note,          // "I want tacos" (AI interprets)
    Long recipeId         // specific recipe, or null for AI to choose
) {}

public enum SkipIntent {
    NO_CHANGE,           // just skip, don't adjust
    MOVE_TO_ANOTHER_DAY, // AI picks best day
    ADJUST_WEEK          // AI rebalances remaining days
}
```

---

## ShoppingService

```java
public interface ShoppingService {
    ShoppingListDto generateFromPlan(Long mealPlanId);
    ShoppingListDto getCurrentList();
    ShoppingListDto getList(Long id);

    void checkItem(Long listId, Long itemId, boolean checked);
    void completeList(Long listId, boolean addToPantry);

    // Tesco ordering
    OrderJobDto orderFromTesco(Long listId);
    OrderJobDto getOrderStatus(String jobId);
}
```

---

## FeedbackService

```java
public interface FeedbackService {
    /**
     * Submit natural language feedback for a meal.
     * AI interprets and scores against rubric.
     */
    FeedbackEntryDto submitFeedback(Long mealSlotId, String rawFeedback);

    List<FeedbackEntryDto> getFeedbackForRecipe(Long recipeId);
    List<FeedbackEntryDto> getRecentFeedback(int count);

    // Preference model
    PreferenceModelDto getCurrentPreferenceModel();
    PreferenceModelDto regeneratePreferenceModel();
}
```

---

## HealthService

```java
public interface HealthService {
    HealthLogDto logCheckIn(CreateHealthLogRequest request);
    List<HealthLogDto> getLogs(LocalDate from, LocalDate to);

    // Reviews
    WeeklyReviewDto getWeeklyReview(LocalDate weekStartDate);
    MonthlyReviewDto getMonthlyReview(YearMonth month);
}
```

---

## NutritionTrackerService

```java
public interface NutritionTrackerService {
    DailyNutritionDto getDailyNutrition(LocalDate date);
    WeeklyNutritionDto getWeeklyNutrition(LocalDate weekStartDate);
    void updateLog(LocalDate date, String mealType, UpdateNutritionLogRequest request);

    // Auto-populate from plan
    void populateFromPlan(Long mealPlanId);
}
```

---

## DiscoveryService

```java
public interface DiscoveryService {
    List<DiscoveredRecipeDto> discoverRecipes(DiscoveryRequest request);
    RecipeDetailDto acceptRecipe(Long discoveredRecipeId);
    void rejectRecipe(Long discoveredRecipeId);
}

public record DiscoveryRequest(
    String focus,      // optional: "quick weeknight dinners"
    int count          // how many to find (default 5)
) {}
```

---

## AiService

```java
public interface AiService {
    /**
     * Send a prompt to Claude and get a parsed response.
     * Handles model routing, template loading, response parsing, cost logging.
     *
     * @param taskType  determines model tier and prompt template
     * @param context   key-value pairs to inject into the prompt template
     * @param responseType  the class to parse the response into
     */
    <T> T execute(AiTaskType taskType, Map<String, Object> context, Class<T> responseType);

    /**
     * Chat-style interaction (for recipe suggestions, feedback interpretation).
     * Maintains conversation context within the call.
     */
    AiChatResponse chat(AiTaskType taskType, Map<String, Object> context, String userMessage);

    /**
     * Get usage stats for a month.
     */
    AiUsageDto getUsage(YearMonth month);
}

public enum AiTaskType {
    // Planner
    RECIPE_SELECTION,        // pass 1 — cheap/mid
    PLAN_ASSEMBLY,           // pass 2 — frontier
    PLAN_ADJUSTMENT,         // frontier

    // Recipe
    RECIPE_IMPORT,           // mid
    RECIPE_GENERATE,         // mid
    RECIPE_EVOLVE,           // mid
    RECIPE_SUGGEST_CHANGES,  // mid (chat)

    // Feedback
    FEEDBACK_INTERPRET,      // mid
    PREFERENCE_MODEL_UPDATE, // mid

    // Nutrition
    INGREDIENT_PARSE,        // cheap
    INGREDIENT_MATCH_USDA,   // cheap

    // Discovery
    RECIPE_DISCOVERY,        // mid

    // Health
    WEEKLY_REVIEW,           // mid
    MONTHLY_REVIEW,          // mid

    // Grocery
    TESCO_PRODUCT_MATCH,     // mid
}
```

---

## NotificationService

```java
public interface NotificationService {
    List<NotificationDto> getUnread();
    void markAsRead(Long id);
    void dismiss(Long id);

    // Called by other modules to create notifications
    void createNotification(CreateNotificationRequest request);

    // Scheduled checks (called by a cron/scheduler)
    void checkExpiringPantryItems();
    void checkDefrostReminders();
    void checkPrepReminders();
}
```

---

## Cross-Cutting: How Modules Talk

Example: marking a meal as cooked touches multiple modules.

```
PlannerService.markAsCooked(planId, slotId)
  │
  ├── Update meal_slot.status = 'cooked'
  ├── RecipeService.incrementTimesCooked(recipeId)
  ├── PantryService.deductIngredients(recipe.ingredients)
  ├── NutritionTrackerService.populateFromPlan(...)  // marks as "as_planned"
  └── return updated MealSlotDto
```

Example: submitting feedback triggers recipe evolution check.

```
FeedbackService.submitFeedback(slotId, rawFeedback)
  │
  ├── AiService.execute(FEEDBACK_INTERPRET, context, FeedbackInterpretation.class)
  ├── Store FeedbackEntry
  ├── RecipeService.updateAvgRating(recipeId)
  ├── if (feedbackCount % 5 == 0):
  │     FeedbackService.regeneratePreferenceModel()
  └── return FeedbackEntryDto
```

Example: generating a plan (the big one).

```
PlannerService.generatePlan(request)
  │
  ├── ProfileService.getProfile()
  ├── FeedbackService.getCurrentPreferenceModel()
  ├── PantryService.getAvailableItems()
  ├── RecipeService.getRecipeIndex()
  ├── AiService.execute(RECIPE_SELECTION, context, RecipeSelectionResult.class)   // pass 1
  │     └── returns List<Long> selectedRecipeIds
  ├── RecipeService.getRecipe(id) for each selected recipe                        // full details
  ├── AiService.execute(PLAN_ASSEMBLY, context, MealPlanResult.class)             // pass 2
  ├── Store MealPlan + MealSlots + IngredientFlows
  └── return MealPlanDto
```
