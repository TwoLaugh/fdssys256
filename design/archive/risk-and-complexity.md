# Risk & Complexity Assessment

## What's Actually Hard

### 1. Tesco Ordering Automation — HARD
**The problem**: Tesco has no public API. You'd need browser automation (Playwright) to:
- Log in to a Tesco account
- Search for each shopping list item
- Pick the right product from search results (this is the hard bit)
- Add correct quantities to basket
- Handle substitutions, out-of-stock, multi-buy deals

**Why it's hard**:
- **Product matching is fuzzy**: your shopping list says "500g chicken breast" but Tesco has "Tesco British Chicken Breast Fillets 640g", "Tesco Chicken Breast Portions 350g", "Organic Free Range Chicken Breast 500g" at 3x the price. Picking the right one requires judgment.
- **Tesco's site changes**: any UI update breaks the automation. This is a maintenance burden.
- **Login/auth**: Tesco uses 2FA, session tokens, CAPTCHAs sometimes. Fragile.
- **Pack sizes don't match recipe quantities**: recipe wants 400g mince, Tesco sells 500g. Do you buy 500g and adjust the pantry? Buy 2x250g?
- **Price optimization**: same item at different sizes/brands at different price points.

**Approach: Claude computer use / Chrome connector**
Claude's browser control capabilities (Chrome connector, computer use API) are already close to handling this — and improving rapidly. The approach:
- Build the integration now against the current capability
- If it's not quite reliable enough today, it will be with the next model release — slot it in without rearchitecting
- The shopping list → Tesco basket flow is actually a well-scoped task: search, pick, add, repeat
- AI handles both navigation AND product matching (the fuzzy bit) in one pass
- User ALWAYS reviews basket before checkout — never auto-confirm payment
- No hardcoded selectors means no breakage when Tesco updates their UI

**Why this is better than Playwright**:
- No selector maintenance — AI reads the page visually
- Product matching is built-in — AI picks the right item using judgment, not string matching
- Handles unexpected UI states (popups, layout changes, "did you mean?" suggestions)
- Gets better for free as models improve

**Remaining risks**:
- Speed: each page interaction requires a model call. A 20-item shop might take a few minutes.
- Cost: manageable for weekly use with a single user, but not negligible
- Auth: 2FA / CAPTCHA might still need manual intervention occasionally

**Verdict**: Build it, accept it might be rough initially, expect it to improve with model releases.

---

### 2. Nutrition Accuracy — MEDIUM-HARD
**The problem**: How do you get reliable nutrition data for arbitrary recipes?

**Available databases**:
- **USDA FoodData Central** (free, comprehensive, API available) — the gold standard for raw ingredients. ~370k foods. Good for "100g chicken breast = 165cal, 31g protein". US-focused but macros are universal.
- **Open Food Facts** (free, open source, API available) — crowd-sourced, better for branded/packaged products. Barcode scanning support. Variable quality but improving. Good for "Tesco Houmous 300g" type items.
- **Nutritionix** (freemium API) — good NLP ("1 cup of brown rice" → nutrition). 1000 free calls/day. Best for parsing natural language quantities.
- **BDA / McCance & Widdowson** — UK government composition tables. The "correct" source for UK foods. Available as a dataset, no API. Would need to import it.

**What's hard**:
- Cooked vs raw quantities: 100g raw chicken ≠ 100g cooked chicken (water loss changes weight by ~25%)
- Recipes that don't specify exact quantities ("a glug of olive oil", "season to taste")
- Composite dishes: nutrition of a stew isn't just sum of ingredients (some nutrients destroyed by heat, fat renders out, etc.)
- Branded products vs generic: "cheddar cheese" varies hugely by brand

**Realistic approach**:
- Use USDA FoodData Central as primary source for raw ingredients (free, reliable, API)
- Use Open Food Facts for any branded/packaged items
- Accept ~10-15% margin of error on calculated nutrition — this is what every calorie tracking app does
- AI estimates where databases don't have an exact match ("this is roughly equivalent to...")
- Don't pretend to be more accurate than we are — show ranges or round numbers

**Verdict**: Good enough is achievable. Perfect accuracy is impossible (and not needed).

---

### 3. Meal Plan Quality — MEDIUM
**The problem**: Generating a meal plan that's genuinely good across all constraints simultaneously.

**What makes it hard**:
- Multi-objective optimization: nutrition targets + taste preferences + variety + pantry usage + budget + cooking time + leftovers management — all at once
- Context window management: the AI needs user profile + pantry + feedback history + recipe library. This gets large.
- Consistency: the plan needs to make sense as a whole week (not just 21 independent meals)
- Avoiding repetition while not being chaotic

**What makes it easier than it sounds**:
- An LLM is actually well-suited to this — it's a reasoning/creativity task, not a pure optimization task
- We can structure the prompt well: constraints first, then preferences, then pantry state
- A single user means the context stays manageable
- Weekly generation = 1 call/week, can afford a strong model

**Verdict**: Will require good prompt engineering but LLMs handle this type of task well.

---

### 4. Recipe Import/Parsing — MEDIUM
**The problem**: Paste a URL, get structured recipe data.

**What makes it hard**:
- Recipe websites are notoriously messy (SEO spam, life stories before the recipe, ads everywhere)
- Ingredient formats vary wildly ("2 cloves garlic, minced" vs "garlic: 2 cloves" vs "minced garlic (2)")
- No standard schema (some use JSON-LD with schema.org Recipe markup, many don't)

**What makes it easier**:
- Many recipe sites DO use schema.org/Recipe structured data — check for that first, parse it, done
- For sites without it, Claude is actually excellent at extracting structured data from messy HTML
- Send page HTML to Claude → get back structured JSON — this works well today
- One-time operation per recipe, not latency-sensitive

**Verdict**: Actually pretty tractable. LLMs are good at this.

---

## What's Straightforward

### Pantry Management
Standard CRUD + simple arithmetic (deductions). The "auto-deduct on cook" is just subtracting recipe ingredients from inventory. Easy.

### Shopping List Generation
Deterministic: (meal plan ingredients) - (pantry stock) = shopping list. Group by category. No AI needed.

### Feedback Storage & Display
Database records + UI. The hard part isn't storing feedback, it's *using* it well (covered by meal plan quality above).

### User Profile & Constraints
A settings form that saves to the database. Straightforward.

### Nutrition Dashboard
Aggregation queries over the meal/nutrition log. Charts. Standard frontend work.

### Recipe CRUD & Versioning
Database design + UI. Recipe versioning is just keeping old versions linked to the same recipe ID. Git for recipes, essentially.

---

## Risk Summary

| Component | Difficulty | Risk | Notes |
|-----------|-----------|------|-------|
| User profile & constraints | Easy | Low | Standard CRUD |
| Pantry management | Easy | Low | CRUD + arithmetic |
| Recipe CRUD & versioning | Easy | Low | Standard DB design |
| Shopping list generation | Easy | Low | Deterministic logic |
| Nutrition dashboard | Easy | Low | Aggregation + charts |
| Feedback storage | Easy | Low | CRUD |
| Recipe import from URL | Medium | Low | LLMs handle this well |
| Nutrition calculation | Medium | Medium | Good enough is fine, perfect is impossible |
| Meal plan generation | Medium | Medium | Prompt engineering challenge |
| Feedback → plan improvement | Medium | Medium | Needs good context management |
| Tesco automation | Hard | High | Fragile, no public API, ongoing maintenance |
