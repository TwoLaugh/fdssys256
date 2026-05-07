# Prompt — Recipe HTML Extraction (Layer 4)

*Cheap-tier last-resort fallback in the recipe extraction pipeline. Receives stripped HTML and returns a `ParsedRecipe` when JSON-LD / h-recipe / per-site extractors have all missed.*

Cross-cutting conventions (confidence scale, null-population, edge-case examples, enum whitelisting, cache strategy, TaskType banner, failure-mode boilerplate) defer to [README.md](README.md). Alt-swap doesn't apply here — the prompt produces one `ParsedRecipe`, not ranked alternatives.

## Wiring

| | |
|---|---|
| AiTask name | `RecipeHtmlExtractionTask` |
| TaskType | `RECIPE_HTML_EXTRACTION` |
| Tier | Haiku 4.5 (cheap) |
| Module | `recipe.extraction` (sub-package of recipe module per [recipe-extraction-pipeline.md](../recipe-extraction-pipeline.md)) |
| Called by | `RecipeExtractionService` as Layer 4 of the five-layer stack — only when Layers 1-3 (JSON-LD, h-recipe, per-site) all miss |
| Input prep | `BoilerplateStripper` removes nav/aside/footer/ads/comments; truncates to ≤30k tokens of recipe-relevant content |
| Failure path | `AiUnavailable` → `ExtractionFailure(reason = AI_UNAVAILABLE, retryAfter = midnight_utc)`; downstream user-driven path surfaces "couldn't extract — manual entry?"; autonomous discovery skips and retries tomorrow |
| Cache | System prompt + 5 examples (~5-7k tokens) cached `ephemeral`; per-call HTML body varies. Comfortably above Haiku 4.5's 4096-token threshold (verified in Cost Analysis). |
| Cost | ~£0.001-0.005/call; ~15-25% of imports reach Layer 4 × ~5 imports/wk = ~£0.01/wk per active user |

## Purpose

Layer 4 is the **last line of defence** in the extraction pipeline. By the time we're calling this prompt, all structured-data paths have failed: no `<script type="application/ld+json">` block, no `class="hrecipe"` markup, no per-site extractor registered for the domain. The HTML is unstructured prose with embedded recipe content — the kind of food blog where the recipe lives in `<p>` tags interleaved with story content.

The prompt produces the same `ParsedRecipe` shape every other layer produces, so Layer 5 (the validator) and the downstream `IngredientLineParser` / tag normaliser don't need to know which layer won. Code does the rough work — boilerplate stripping, dedup, validation; the model does the qualitative work of separating recipe content from story padding.

This is **not** a "find the recipe on this site" prompt. The HTML has already been stripped to recipe-relevant content. The model extracts from whatever the stripper left and does not try to "find more" beyond what's given. If the stripper killed everything (JS-rendered SPA), the prompt returns an empty `ParsedRecipe` with a warning.

## Inputs / Outputs

**Inputs (passed via `AiTask.getContext()`):**

```java
Map.of(
    "html_content",   String,    // stripped HTML, ≤30k tokens — see Decisions §1
    "source_url",     String,    // full URL; used for multi-recipe roundup disambiguation
    "source_domain",  String     // extracted from URL
)
```

**Output: the canonical `ParsedRecipe` record** from [recipe-extraction-pipeline.md §ParsedRecipe data shape](../recipe-extraction-pipeline.md#parsedrecipe-data-shape). The model populates the **content** fields only — `name`, `description`, `servings`, `prepTimeMinutes`, `cookTimeMinutes`, `totalTimeMinutes`, `ingredients` (list of `ParsedIngredient`), `methodSteps` (list of `ParsedMethodStep`), `rawCuisineTags`, `rawCategoryTags`, `rawKeywords`, `validationWarnings`. Code populates `sourceUrl`, `sourceDomain`, `contentHash`, `provenance` post-extraction; Layer 5 may extend `validationWarnings`.

Notable nullability (deviates slightly from the canonical shape only in *which* fields the model is responsible for):

- `name`: nullable — null when HTML isn't a recipe (paired-null with empty `ingredients`/`methodSteps`).
- `description`: nullable — one-line teaser when present; never used to dump story content.
- `servings`, `totalTimeMinutes`: primitive `int`; `0` is the "unknown" sentinel (Java records can't null-out primitives — see Decisions §9).
- `prepTimeMinutes`, `cookTimeMinutes`: `Integer`, nullable when unspecified.
- `ingredients`, `methodSteps`, `raw*Tags`, `validationWarnings`: required, never null; empty `[]` when absent.

`ParsedIngredient` shape is unchanged from the pipeline doc — `rawText` (verbatim source line; see Decisions §4), `quantity`, `unit`, `name`, `preparation`, `optional`, `unparseable`.

## System Prompt

```
You are a recipe-content extractor. Given the stripped HTML of a webpage, produce a structured ParsedRecipe — name, ingredients, method steps, timings, tags. Your output is consumed by a deterministic validator and a downstream ingredient-line parser; faithful extraction matters more than clever interpretation.

IS THIS A RECIPE? A page is a recipe iff it has ALL THREE: a dish name; an ingredient list with >2 items; a method with >2 steps. If any is missing, return name=null, ingredients=[], methodSteps=[], with a warning naming what the page actually is (collection page, about page, blog post about food, error page).

EXTRACT:
1. Name — the dish title, not the page title. Strip site-branding suffixes ("| BBC Good Food", " - Recipe Name | Site").
2. Description — one-line teaser if present (e.g. `<p class="intro">`); null if absent. Never use story content as description.
3. Servings — parse "Serves 4", "Makes 12 cookies", "4-6 portions" → integer; lower bound for ranges; 0 when unknown.
4. Times — prepTimeMinutes / cookTimeMinutes / totalTimeMinutes from "Prep: 15 min", "Cook: 1 hr 30 mins", etc. Null when not specified; totalTimeMinutes = sum of components, or 0 if all unknown.
5. Ingredients — each entry's rawText is the source line VERBATIM (preserve fractions "½", parentheticals "(about 2 cups)", brand names "Heinz", dual units "1 cup / 240ml"). Best-effort parse quantity/unit/name/preparation; set unparseable=true when you can't. quantity=null for non-numeric ("a pinch of salt"); preparation captures "diced", "softened", etc.; optional=true on "(optional)" markers.
6. Method steps — extract sequentially. Re-number from 1 regardless of source format ("First, ..." / bullets / no markers). Keep multi-sentence steps as one step unless the source clearly delineates.
7. Cuisine / category / keyword tags — RAW text from the page ("Italian", "weeknight dinner", "vegetarian"). Do not normalise onto any canonical vocabulary; the recipe module's tagger does that. Empty list when none.

IGNORE: story preambles (many blogs have 1500+ words of personal narrative before the recipe card — ignore entirely); related-recipes / "you might also like" sidebars; nutritional information (we recompute internally); author photos, ad placements, social widgets; comment sections; photo alt-text (it can contain unrelated dish names — never treat alt-text as recipe content).

MULTI-RECIPE PAGES (e.g. "5 weeknight pastas" roundup): pick in this order — (1) URL fragment or path segment naming a recipe; (2) recipe whose name appears in the URL or page <title>; (3) the first complete recipe (has all three criteria). Add a warning naming the alternatives present.

INGREDIENT-LINE PRESERVATION: rawText is the source line VERBATIM. The downstream IngredientLineParser re-parses with stricter rules; your quantity/unit/name fields are best-effort hints for it.

CONFIDENCE — N/A. This prompt produces a single ParsedRecipe rather than a ranked match. No per-output confidence score. Quality concerns surface as validationWarnings. When uncertain about a field, populate your best guess and add a warning explaining ("servings inferred from 'feeds a crowd'; treat as approximate").

WARN ON EDGE CASES:
- HTML mostly empty (JS-rendered SPA shell after stripping) → all-null content + warning suggesting re-import via the in-app browser path.
- Recipe content fragmented (visible "show more" / collapsed-accordion remnants) → extract what's visible + warning content may be incomplete.
- Recipe-like fragments without an actual structured recipe (collection / index / category page) → all-null content + warning naming the page type.
- Ingredients and method appear split across sub-pages ("Continue to the method →") → extract what you have + warning about the split.

DO NOT: invent ingredients or steps; normalise units / brands / measurements in rawText; skip the three-criterion recipe check; treat sidebar fragments as the target recipe.
```

## User Prompt Template

```
[Task: RECIPE_HTML_EXTRACTION]

<source>
url: {{SOURCE_URL}}
domain: {{SOURCE_DOMAIN}}
</source>

<html>
{{HTML_CONTENT}}
</html>

Extract the recipe per the rules above. If this page isn't a recipe (per the three-criterion check), return all-null content with an explanatory warning.
```

The `[Task: ...]` banner is the convention from [README.md §TaskType banner](README.md). Comes after the cached prefix; identifies the task in the call-log audit. `{{HTML_CONTENT}}` is the only large placeholder — typically 10-25k tokens after stripping.

## Tool Schema

JSON schema generated from `ParsedRecipe` via `victools/jsonschema-generator` per [ai.md §Flow 3](../ai.md#flow-3-structured-output-parsing). Notable constraints:

- `name`: `{"type": ["string", "null"]}` — null when not a recipe
- `unit` (in each `ParsedIngredient`): enum-whitelisted per [README.md §Enum-string fields](README.md#enum-string-fields-use-whitelisted-values) — same vocabulary as USDA mapping (`g`, `kg`, `ml`, `l`, `tbsp`, `tsp`, `cup`, `oz`, `lb`, `piece`, `can`, `bottle`, `jar`, `sprig`, `clove`, `pinch`, `dash`, `drop`, `other`, `null`)
- `ingredients`, `methodSteps`, `rawCuisineTags`, `rawCategoryTags`, `rawKeywords`, `validationWarnings`: required, never null; empty `[]` when absent
- `servings`, `totalTimeMinutes`: required integers; `0` is the "unknown" sentinel (paired-null doesn't fit `int`)
- `prepTimeMinutes`, `cookTimeMinutes`: nullable integers; null when not specified

`provenance`, `sourceUrl`, `sourceDomain`, `contentHash` are NOT in the tool schema — they're set by the calling code post-extraction. The schema only covers the model-populated fields.

## Examples (in-prompt, wrapped in `<examples>` tags)

```
<examples>

<example>
<input>
<source>url: https://exampleblog.com/recipes/lemon-chicken | domain: exampleblog.com</source>
<html><h1>Easy Lemon Chicken</h1><p>A bright weeknight dinner ready in 30 minutes.</p>
<h2>Ingredients</h2><ul><li>4 boneless chicken thighs</li><li>2 lemons, juiced</li><li>3 tbsp olive oil</li><li>2 cloves garlic, minced</li><li>Salt and pepper to taste</li></ul>
<h2>Method</h2><ol><li>Pat chicken dry and season generously.</li><li>Heat olive oil in a pan over medium-high heat.</li><li>Cook chicken 6 minutes per side until golden.</li><li>Add lemon juice and garlic; cook 2 more minutes.</li><li>Rest 5 minutes before serving.</li></ol>
<p>Prep: 5 min | Cook: 15 min | Serves 4 | Cuisine: Mediterranean</p></html>
</input>
<output>
{ "name": "Easy Lemon Chicken", "description": "A bright weeknight dinner ready in 30 minutes.",
  "servings": 4, "prepTimeMinutes": 5, "cookTimeMinutes": 15, "totalTimeMinutes": 20,
  "ingredients": [
    {"rawText":"4 boneless chicken thighs","quantity":4,"unit":"piece","name":"boneless chicken thighs","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"2 lemons, juiced","quantity":2,"unit":"piece","name":"lemons","preparation":"juiced","optional":false,"unparseable":false},
    {"rawText":"3 tbsp olive oil","quantity":3,"unit":"tbsp","name":"olive oil","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"2 cloves garlic, minced","quantity":2,"unit":"clove","name":"garlic","preparation":"minced","optional":false,"unparseable":false},
    {"rawText":"Salt and pepper to taste","quantity":null,"unit":null,"name":"salt and pepper","preparation":null,"optional":false,"unparseable":true}
  ],
  "methodSteps": [
    {"stepNumber":1,"instruction":"Pat chicken dry and season generously."},
    {"stepNumber":2,"instruction":"Heat olive oil in a pan over medium-high heat."},
    {"stepNumber":3,"instruction":"Cook chicken 6 minutes per side until golden."},
    {"stepNumber":4,"instruction":"Add lemon juice and garlic; cook 2 more minutes."},
    {"stepNumber":5,"instruction":"Rest 5 minutes before serving."}
  ],
  "rawCuisineTags": ["Mediterranean"], "rawCategoryTags": [], "rawKeywords": ["weeknight"], "validationWarnings": []
}
</output>
</example>

<example>
<input>
<source>url: https://foodblog.com/granny-pasta | domain: foodblog.com</source>
<html><h1>Granny's Sunday Pasta — A Family Story</h1>
<p>Every Sunday, my grandmother would wake up at 5am to start the sauce. The kitchen would fill with the smell of slow-simmered tomatoes and basil from her garden. I remember sitting on the counter, watching her hands work the dough... [1500 words of family story] ...and that's how this recipe came into our family.</p>
<div class="recipe-card"><h2>Granny's Sunday Pasta</h2>
<p>Serves: 6 | Prep: 30 min | Cook: 3 hours</p>
<h3>Ingredients</h3><ul><li>500g pappardelle</li><li>1kg ripe tomatoes, peeled</li><li>1 onion, diced</li><li>4 cloves garlic</li><li>Fresh basil, large handful</li><li>100ml olive oil</li><li>Salt to taste</li></ul>
<h3>Method</h3><ol><li>Sweat the onion in olive oil over low heat for 15 minutes.</li><li>Add garlic; cook 1 minute.</li><li>Add tomatoes; simmer uncovered for 2.5 hours, stirring occasionally.</li><li>Cook pappardelle to al dente.</li><li>Toss pasta with sauce; finish with basil.</li></ol></div></html>
</input>
<output>
{ "name": "Granny's Sunday Pasta", "description": null,
  "servings": 6, "prepTimeMinutes": 30, "cookTimeMinutes": 180, "totalTimeMinutes": 210,
  "ingredients": [
    {"rawText":"500g pappardelle","quantity":500,"unit":"g","name":"pappardelle","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"1kg ripe tomatoes, peeled","quantity":1,"unit":"kg","name":"ripe tomatoes","preparation":"peeled","optional":false,"unparseable":false},
    {"rawText":"1 onion, diced","quantity":1,"unit":"piece","name":"onion","preparation":"diced","optional":false,"unparseable":false},
    {"rawText":"4 cloves garlic","quantity":4,"unit":"clove","name":"garlic","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"Fresh basil, large handful","quantity":null,"unit":null,"name":"fresh basil","preparation":null,"optional":false,"unparseable":true},
    {"rawText":"100ml olive oil","quantity":100,"unit":"ml","name":"olive oil","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"Salt to taste","quantity":null,"unit":null,"name":"salt","preparation":null,"optional":false,"unparseable":true}
  ],
  "methodSteps": [
    {"stepNumber":1,"instruction":"Sweat the onion in olive oil over low heat for 15 minutes."},
    {"stepNumber":2,"instruction":"Add garlic; cook 1 minute."},
    {"stepNumber":3,"instruction":"Add tomatoes; simmer uncovered for 2.5 hours, stirring occasionally."},
    {"stepNumber":4,"instruction":"Cook pappardelle to al dente."},
    {"stepNumber":5,"instruction":"Toss pasta with sauce; finish with basil."}
  ],
  "rawCuisineTags": [], "rawCategoryTags": [], "rawKeywords": [],
  "validationWarnings": ["Story preamble of approximately 1500 words ignored; recipe extracted from the structured recipe card section only."]
}
</output>
</example>

<example>
<input>
<source>url: https://example.com/5-weeknight-pastas#carbonara | domain: example.com</source>
<html><h1>5 Weeknight Pasta Recipes</h1><p>Quick pastas for busy nights.</p>
<h2 id="aglio-olio">1. Spaghetti Aglio e Olio</h2><p>Serves 2 | 15 min</p>
<h3>Ingredients</h3><ul><li>200g spaghetti</li><li>4 cloves garlic, sliced</li><li>50ml olive oil</li><li>Chilli flakes, parsley</li></ul>
<h3>Method</h3><ol><li>Boil pasta.</li><li>Sizzle garlic in oil.</li><li>Toss pasta with garlic oil; finish with parsley.</li></ol>
<h2 id="carbonara">2. Classic Carbonara</h2><p>Serves 4 | 20 min</p>
<h3>Ingredients</h3><ul><li>400g spaghetti</li><li>200g guanciale, diced</li><li>4 large eggs</li><li>100g pecorino, grated</li><li>Black pepper</li></ul>
<h3>Method</h3><ol><li>Cook pasta in salted water.</li><li>Render guanciale until crisp.</li><li>Whisk eggs with pecorino and pepper.</li><li>Combine off heat; toss vigorously with reserved pasta water.</li></ol>
<h2 id="cacio-e-pepe">3. Cacio e Pepe</h2><p>...</p></html>
</input>
<output>
{ "name": "Classic Carbonara", "description": null,
  "servings": 4, "prepTimeMinutes": null, "cookTimeMinutes": null, "totalTimeMinutes": 20,
  "ingredients": [
    {"rawText":"400g spaghetti","quantity":400,"unit":"g","name":"spaghetti","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"200g guanciale, diced","quantity":200,"unit":"g","name":"guanciale","preparation":"diced","optional":false,"unparseable":false},
    {"rawText":"4 large eggs","quantity":4,"unit":"piece","name":"large eggs","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"100g pecorino, grated","quantity":100,"unit":"g","name":"pecorino","preparation":"grated","optional":false,"unparseable":false},
    {"rawText":"Black pepper","quantity":null,"unit":null,"name":"black pepper","preparation":null,"optional":false,"unparseable":true}
  ],
  "methodSteps": [
    {"stepNumber":1,"instruction":"Cook pasta in salted water."},
    {"stepNumber":2,"instruction":"Render guanciale until crisp."},
    {"stepNumber":3,"instruction":"Whisk eggs with pecorino and pepper."},
    {"stepNumber":4,"instruction":"Combine off heat; toss vigorously with reserved pasta water."}
  ],
  "rawCuisineTags": [], "rawCategoryTags": [], "rawKeywords": ["weeknight"],
  "validationWarnings": ["Page contains 5 recipes (Spaghetti Aglio e Olio, Classic Carbonara, Cacio e Pepe, plus 2 more); URL fragment '#carbonara' identified Carbonara as the target."]
}
</output>
</example>

<example>
<input>
<source>url: https://uk-blog.com/lemon-drizzle | domain: uk-blog.com</source>
<html><h1>Lemon Drizzle Cake</h1><p>The perfect afternoon-tea bake.</p>
<h2>Ingredients</h2><ul><li>225g (1 cup + 2 tbsp) unsalted butter, softened</li><li>225g (1 cup + 2 tbsp) caster sugar</li><li>4 large eggs</li><li>225g (1 3/4 cups) self-raising flour</li><li>Zest of 2 lemons</li><li>For the drizzle: 100g (1/2 cup) caster sugar + juice of 2 lemons</li></ul>
<h2>Method</h2><ol><li>Cream butter and sugar until pale.</li><li>Beat in eggs one at a time.</li><li>Fold in flour and lemon zest.</li><li>Bake at 180°C / 350°F for 45 minutes.</li><li>Mix drizzle ingredients; pour over warm cake.</li></ol>
<p>Serves 8</p></html>
</input>
<output>
{ "name": "Lemon Drizzle Cake", "description": "The perfect afternoon-tea bake.",
  "servings": 8, "prepTimeMinutes": null, "cookTimeMinutes": 45, "totalTimeMinutes": 45,
  "ingredients": [
    {"rawText":"225g (1 cup + 2 tbsp) unsalted butter, softened","quantity":225,"unit":"g","name":"unsalted butter","preparation":"softened","optional":false,"unparseable":false},
    {"rawText":"225g (1 cup + 2 tbsp) caster sugar","quantity":225,"unit":"g","name":"caster sugar","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"4 large eggs","quantity":4,"unit":"piece","name":"large eggs","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"225g (1 3/4 cups) self-raising flour","quantity":225,"unit":"g","name":"self-raising flour","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"Zest of 2 lemons","quantity":2,"unit":"piece","name":"lemon zest","preparation":null,"optional":false,"unparseable":false},
    {"rawText":"For the drizzle: 100g (1/2 cup) caster sugar + juice of 2 lemons","quantity":null,"unit":null,"name":"drizzle (sugar + lemon juice)","preparation":null,"optional":false,"unparseable":true}
  ],
  "methodSteps": [
    {"stepNumber":1,"instruction":"Cream butter and sugar until pale."},
    {"stepNumber":2,"instruction":"Beat in eggs one at a time."},
    {"stepNumber":3,"instruction":"Fold in flour and lemon zest."},
    {"stepNumber":4,"instruction":"Bake at 180°C / 350°F for 45 minutes."},
    {"stepNumber":5,"instruction":"Mix drizzle ingredients; pour over warm cake."}
  ],
  "rawCuisineTags": [], "rawCategoryTags": [], "rawKeywords": ["afternoon tea", "bake"],
  "validationWarnings": ["Ingredients use dual metric+imperial units; metric (grams) preserved as primary parse, imperial preserved verbatim in rawText. The drizzle line is a compound ingredient — flagged unparseable for downstream re-parse."]
}
</output>
</example>

<example>
<input>
<source>url: https://example.com/about-our-blog | domain: example.com</source>
<html><h1>About Our Food Blog</h1><p>Welcome to our food blog! We publish home-style recipes every Tuesday and Friday. Our editor Jane has been writing about food for 15 years. We focus on weeknight dinners that feed a family of four. Browse our recipe archive for hundreds of dishes, or sign up for our newsletter.</p><p>Recent posts: Best chicken thigh recipes, 30-minute pasta dishes, gluten-free desserts that actually taste good.</p></html>
</input>
<output>
{ "name": null, "description": null,
  "servings": 0, "prepTimeMinutes": null, "cookTimeMinutes": null, "totalTimeMinutes": 0,
  "ingredients": [], "methodSteps": [],
  "rawCuisineTags": [], "rawCategoryTags": [], "rawKeywords": [],
  "validationWarnings": ["Page is an 'About' / blog-info page, not a recipe. It mentions recipe topics (chicken thighs, pasta, desserts) but contains no ingredient list and no method steps. Recommend the user pick a specific recipe URL from the archive instead."]
}
</output>
</example>

</examples>
```

## Eval Set (regression)

~18 cases beyond the in-prompt examples. Used by `RecipeHtmlExtractionTaskTest` and during prompt iteration. Stored at `src/test/resources/prompts/recipe-html-extraction-eval.json`.

| # | Input | Expected | Tests |
|---|---|---|---|
| 1 | Clean food blog with structured recipe card | Complete `ParsedRecipe`, no warnings | Happy path |
| 2 | Food blog with 2000-word personal story before recipe | Recipe extracted; story ignored; warning notes story length | Story separation (§3) |
| 3 | Multi-recipe roundup, URL fragment `#carbonara` | Picks Carbonara; warning lists others | URL-fragment disambiguation (§2) |
| 4 | Multi-recipe roundup, no fragment, page `<title>` says "Lasagne" | Picks Lasagne; warning notes alternatives | Title-based disambiguation |
| 5 | Multi-recipe roundup, no disambiguation signal | Picks first complete recipe; warning | First-complete fallback |
| 6 | Recipe with metric+imperial dual units (`225g (1 cup) butter`) | rawText verbatim; metric primary; warning | Dual-unit handling (§4) |
| 7 | Recipe with no time fields specified | Times null/0; rest populated | Optional time-field handling |
| 8 | Source uses bullet markers ("- First, ...") not numbers | Steps re-numbered 1, 2, 3... | Method-step renumbering (§5) |
| 9 | About / blog-info page (no recipe) | All-null content + warning | Page-isn't-a-recipe (mandatory edge case) |
| 10 | JS-rendered SPA shell — mostly empty stripped HTML | All-null + warning suggesting in-app browser | Empty-HTML degradation |
| 11 | Photo alt-text contains other dish names | Cake recipe extracted; alt-text ignored | Alt-text resistance |
| 12 | Recipe inside `<details>` / accordion sections | Extracts collapsed content; warning if fragmented | Collapsed-content handling |
| 13 | "Continue to method on next page →"; ingredients only here | Ingredients populated; methodSteps empty; warning | Cross-page recipe |
| 14 | "Serves 4-6" range | servings = 4 (lower bound) | Range handling |
| 15 | "Makes 24 cookies" | servings = 24 | Yield-as-count |
| 16 | UK vernacular ("Crumpets", "rocket", "courgette") | Extracted normally; terms preserved | Regional-vernacular preservation |
| 17 | "Best 10 X" list page with names + photos but no per-entry ingredients/methods | All-null + warning | List-page detection |
| 18 | Recipe with `(optional)` markers on some lines | optional=true on those lines | Optional-marker parsing |

Acceptance threshold per [README.md §Eval-set discipline](README.md#eval-set-discipline): **16/18** for ship; **13/18** acceptable for first deployment.

## Cost Analysis

| Metric | Estimate |
|---|---|
| Input tokens (per call, after cache hit) | ~10-30k (HTML body dominates) |
| Cached input tokens | ~5-7k (system prompt + 5 examples + AiTask preamble) — **above Haiku 4.5's 4096 threshold** ✓ |
| Output tokens | ~500-1500 (`ParsedRecipe` is verbose due to per-ingredient structure) |
| Cost per call (Haiku 4.5 with cache hit) | **~£0.001-0.005** |
| Cost per call (cold cache) | ~£0.005-0.010 |
| Layer 4 invocation rate | ~15-25% of imports (Layers 1-3 catch the majority) |
| Recipe imports per active user per week | ~5 |
| Layer 4 calls per active user per week | ~1-2 |
| **Cost per user per week** | **~£0.01** |
| Cache hit rate target | >70% (cache TTL 5 min; user-driven imports are sporadic, but autonomous discovery batches hit cache aggressively) |

**Cold-start discovery** (first onboarding pass over curated sources): ~10-20 Layer 4 calls × £0.005 ≈ **£0.10 once-off** per source, distributed across the autonomous discovery schedule.

The cached prefix sits comfortably above the 4096-token threshold thanks to the 5 substantive HTML examples — no padding required. Verify in production by inspecting `cache_creation_input_tokens` on the first call and `cache_read_input_tokens` on subsequent calls within the 5-minute window per [README.md §Cache strategy](README.md#cache-strategy--minimum-prefix-length).

## Failure Modes

Standard rows from [README.md §Failure-mode boilerplate](README.md#failure-mode-boilerplate) apply — tool-use validation failure, `AiUnavailable`, output-references-id-not-in-inputs (N/A here), low confidence (N/A here). Task-specific extensions:

| Failure | Detection | Behaviour |
|---|---|---|
| HTML mostly empty (JS-rendered SPA — stripping killed everything) | Code: stripped HTML <500 chars (skips AI call); Model: all-null + warning | User-driven path surfaces "page may be JS-rendered; try the in-app browser to capture rendered DOM". Autonomous path skips. |
| Content fragmented across `<details>` / accordion sections | Model warning + Layer 5 soft-fail | User path: surface warning with edit affordances. Autonomous: reject if `discovery_sources.minQualityThreshold` high; otherwise accept with provenance flag. |
| Page is collection / list / about (not a recipe) | Model: all-null + warning | Layer 5 maps to `EXTRACTION_INSUFFICIENT` per [recipe-extraction-pipeline.md §Failure modes](../recipe-extraction-pipeline.md#failure-modes-consolidated). User path offers manual entry; autonomous skips and logs (don't retry — the page genuinely isn't a recipe). |
| Multi-recipe page, ambiguous target | Model picks first complete + warning | User path: "did we pick the right one?" affordance. Autonomous: accept the pick; repeated ambiguity on a domain → candidate for a per-site Layer 3 extractor. |
| HTML exceeds 30k tokens even after stripping | Code-side token check before AI call | Hard-truncate keeping recipe-content section; if truncation drops recipe content, fall back to `EXTRACTION_INSUFFICIENT`. See Decisions §1. |
| Method steps empty but ingredients populated | Layer 5 validator | Soft-fail with warning. User path: add steps manually. Autonomous: reject unless source has high `quality_score`. |
| Servings = 0 (unknown) | Layer 5 validator | Soft-fail with warning. UI defaults to 4 servings; user confirms. |

## AiTask Skeleton

```java
package com.example.mealprep.recipe.extraction.domain.service.internal;

import com.example.mealprep.ai.spi.*;
import com.example.mealprep.recipe.extraction.ParsedRecipe;
import java.time.Duration;
import java.util.*;

public final class RecipeHtmlExtractionTask implements AiTask<ParsedRecipe> {
    private final String htmlContent, sourceUrl, sourceDomain;   // htmlContent already stripped + truncated to ≤30k tokens
    private final UUID userId, traceId;                          // userId nullable for autonomous discovery

    public RecipeHtmlExtractionTask(String htmlContent, String sourceUrl, String sourceDomain, UUID userId, UUID traceId) { /* assignments */ }

    @Override public TaskType getTaskType() { return TaskType.RECIPE_HTML_EXTRACTION; }
    @Override public String getSystemPrompt() { return SYSTEM_PROMPT; }   // loaded from prompts/recipe/html-extraction-system.txt
    @Override public PromptRef getUserPromptRef() { return new PromptRef("recipe/html-extraction-user", Optional.empty()); }
    @Override public Map<String, Object> getContext() {
        return Map.of("html_content", htmlContent, "source_url", sourceUrl, "source_domain", sourceDomain);
    }
    @Override public ToolDefinition getToolSchema() {
        return ToolDefinitionBuilder.fromRecord(ParsedRecipe.class)
            .name("report_parsed_recipe")
            .description("Report the structured recipe extracted from the page HTML.").build();
    }
    @Override public Class<ParsedRecipe> getResponseType() { return ParsedRecipe.class; }
    @Override public UUID getUserId() { return userId; }
    @Override public UUID getTraceId() { return traceId; }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.of(Duration.ofSeconds(30)); }
}
```

The 30-second timeout (vs cheap-tier default ~10s) accommodates the large HTML input + verbose structured output. Per-task timeout per [ai.md §SPI](../ai.md#spi--what-calling-modules-implement).

## Decisions made (worth user review)

1. **HTML truncation is code's responsibility, not the model's.** `BoilerplateStripper` removes nav/aside/footer/ads; a post-strip truncator drops sidebars, related-recipes blocks, footer remnants, collapsed accordion content beyond the first level. The prompt explicitly states the model receives stripped content and should not try to "find more" beyond what's given. If truncation can't fit recipe content within 30k tokens, fall back to `EXTRACTION_INSUFFICIENT` rather than truncating mid-recipe.

2. **Multi-recipe disambiguation — three-step.** (a) URL fragment / path segment naming a recipe → pick it; (b) recipe whose name appears in URL or page `<title>`; (c) first complete recipe (all three criteria) + warning naming the others. URL-driven is unambiguous when present; first-complete is the safest default for autonomous discovery. Repeated ambiguity on a domain is a signal to write a per-site Layer 3 extractor.

3. **Story-vs-recipe separation.** Many food blogs precede the recipe with 1500+ words of personal narrative. The prompt instructs the model to ignore story content and extract only the structured recipe. Story content is never returned as `description` — that field is reserved for one-line teasers when present.

4. **Ingredient line preservation — verbatim `rawText`.** Preserves fractions, parentheticals, brand names, dual units. The model's parsed `quantity`/`unit`/`name` fields are best-effort hints; the downstream `IngredientLineParser` (canonical per [recipe-extraction-pipeline.md §IngredientLineParser](../recipe-extraction-pipeline.md#ingredientlineparser)) may re-parse with stricter rules. The parser always re-parses from `rawText` — avoids the model and parser disagreeing.

5. **Method step renumbering — always sequential from 1.** Regardless of source format (bullets, "First/Then/Finally" prose, no markers, already-numbered). Downstream renderer assumes 1-based numbering; preserving source numbering creates ambiguity on merge/edit. Original prose preserved in `instruction`; only `stepNumber` is normalised.

6. **Cuisine / category tags are RAW — no normalisation.** Recipe module's tagger normalises onto the project's tag vocabulary post-extraction. Keeping this prompt purely extractive separates "what does the page say?" from "what does our taxonomy say?" — testable and swappable separately.

7. **Page-isn't-a-recipe shape.** `name=null`, lists empty `[]`, integer sentinels `0` (`int` can't be null in Java records), warning explains what the page actually is. Per [README.md §Null-population rules](README.md#null-population-rules). Layer 5 maps this shape to `EXTRACTION_INSUFFICIENT`. Demonstrated in Example 5 (the mandatory edge case).

8. **No confidence score.** Single `ParsedRecipe` rather than a ranked match — quality concerns surface as `validationWarnings`. README's confidence-scale convention does not apply; system prompt explicitly says so to prevent the model inventing one.

9. **Servings as `int`/0 sentinel, not `Integer`/null.** Matches the `ParsedRecipe` record shape; 0 is the "unknown" sentinel because Java records use primitive `int`. Downstream UI defaults to 4 with user confirmation. Promoting to `Integer` is out of scope for this prompt doc; flag for the pipeline LLD if reconsidered.

10. **Cache-prefix above threshold without padding.** System prompt (~2k tokens) + 5 substantive HTML examples (~3-5k tokens) puts the cached prefix at ~5-7k — above Haiku 4.5's 4096 minimum. Unlike the USDA mapping prompt, no padding needed. Verify on first production call via `cache_creation_input_tokens`.

11. **Eval-set size: 18 cases (vs USDA mapping's 20).** Lower-volume path → fewer cases needed for regression confidence. Compensated by higher complexity per case (each ships a non-trivial HTML fixture).

12. **30-second timeout override.** Cheap-tier default (~10s) is too tight for ~30k-token inputs with verbose `ParsedRecipe` outputs. 30s gives headroom; the user-driven import flow is async behind an "extracting…" spinner anyway.
