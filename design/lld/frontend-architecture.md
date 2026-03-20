# Frontend Architecture

## Tech Stack

- **React 18** with TypeScript
- **Vite** for build tooling (faster than CRA)
- **React Router** for navigation
- **TanStack Query (React Query)** for server state / API calls / caching
- **Zustand** for minimal client state (UI state, not server data)
- **Tailwind CSS** for styling
- **Recharts** for nutrition charts
- **date-fns** for date formatting

### Why These Choices
- **TanStack Query over Redux**: most state is server-derived (recipes, plans, pantry). TanStack Query handles fetching, caching, invalidation, and optimistic updates natively. No boilerplate.
- **Zustand over Redux/Context**: for the small amount of true client state (sidebar open, selected date, theme), Zustand is minimal and doesn't require providers.
- **Tailwind over CSS-in-JS**: fast to build, responsive utilities built in, no runtime cost. Good for a single developer.

---

## Project Structure

```
frontend/
├── public/
├── src/
│   ├── api/                    ← API client layer
│   │   ├── client.ts           ← base fetch wrapper (auth, error handling)
│   │   ├── profile.ts          ← ProfileService API calls
│   │   ├── recipes.ts          ← RecipeService API calls
│   │   ├── pantry.ts
│   │   ├── planner.ts
│   │   ├── shopping.ts
│   │   ├── feedback.ts
│   │   ├── nutrition.ts
│   │   ├── health.ts
│   │   ├── discovery.ts
│   │   └── notifications.ts
│   │
│   ├── hooks/                  ← Custom React hooks (wrapping TanStack Query)
│   │   ├── useProfile.ts
│   │   ├── useRecipes.ts
│   │   ├── usePantry.ts
│   │   ├── usePlanner.ts
│   │   └── ...
│   │
│   ├── components/             ← Shared/reusable components
│   │   ├── ui/                 ← Generic UI primitives
│   │   │   ├── Button.tsx
│   │   │   ├── Card.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── Input.tsx
│   │   │   ├── Badge.tsx
│   │   │   ├── ProgressBar.tsx
│   │   │   └── ...
│   │   ├── layout/
│   │   │   ├── AppShell.tsx    ← Main layout (nav + content area)
│   │   │   ├── BottomNav.tsx   ← Mobile bottom navigation
│   │   │   ├── Sidebar.tsx     ← Desktop sidebar navigation
│   │   │   └── TopBar.tsx
│   │   ├── NutritionBar.tsx    ← Macro progress bar (reused everywhere)
│   │   ├── RecipeCard.tsx      ← Recipe card for grid views
│   │   ├── MealSlotCard.tsx    ← Meal card for plan/dashboard views
│   │   ├── ChatSidebar.tsx     ← AI chat panel (reused on recipe detail)
│   │   └── NotificationBell.tsx
│   │
│   ├── pages/                  ← Page-level components (one per route)
│   │   ├── Dashboard/
│   │   │   ├── Dashboard.tsx
│   │   │   ├── TodaysMeals.tsx
│   │   │   ├── NutritionSummary.tsx
│   │   │   └── AlertsList.tsx
│   │   ├── Plan/
│   │   │   ├── WeeklyPlan.tsx
│   │   │   ├── PlanGrid.tsx
│   │   │   ├── GeneratePlanModal.tsx
│   │   │   └── SlotActions.tsx      ← skip/swap/override actions
│   │   ├── Recipes/
│   │   │   ├── RecipeLibrary.tsx
│   │   │   ├── RecipeDetail.tsx
│   │   │   ├── RecipeHistory.tsx    ← version history tab
│   │   │   ├── AddRecipeModal.tsx
│   │   │   ├── ImportRecipeModal.tsx
│   │   │   └── CookingMode.tsx      ← step-by-step kitchen view
│   │   ├── Pantry/
│   │   │   ├── PantryView.tsx
│   │   │   ├── PantryItemRow.tsx
│   │   │   ├── AddItemModal.tsx
│   │   │   └── WasteLog.tsx
│   │   ├── Shopping/
│   │   │   ├── ShoppingList.tsx
│   │   │   ├── ShoppingItem.tsx
│   │   │   └── TescoOrderStatus.tsx
│   │   ├── Nutrition/
│   │   │   ├── DailyView.tsx
│   │   │   ├── WeeklyView.tsx
│   │   │   └── MacroCharts.tsx
│   │   ├── Health/
│   │   │   ├── CheckIn.tsx
│   │   │   ├── WeightChart.tsx
│   │   │   ├── SymptomHistory.tsx
│   │   │   └── Reviews.tsx
│   │   ├── Discovery/
│   │   │   ├── DiscoveryFeed.tsx
│   │   │   └── DiscoveredRecipeCard.tsx
│   │   └── Settings/
│   │       ├── ProfileSettings.tsx
│   │       ├── DietaryConstraints.tsx
│   │       ├── NutritionGoals.tsx
│   │       ├── CookingPreferences.tsx
│   │       ├── PreferenceModelViewer.tsx
│   │       └── AiUsage.tsx
│   │
│   ├── store/                  ← Zustand stores (client-only state)
│   │   └── uiStore.ts         ← sidebar open, selected date, etc.
│   │
│   ├── types/                  ← TypeScript types matching API contracts
│   │   ├── profile.ts
│   │   ├── recipe.ts
│   │   ├── pantry.ts
│   │   ├── planner.ts
│   │   └── ...
│   │
│   ├── utils/                  ← Pure utility functions
│   │   ├── dates.ts
│   │   ├── nutrition.ts        ← formatting, % calculations
│   │   └── formatting.ts
│   │
│   ├── App.tsx                 ← Router setup
│   ├── main.tsx                ← Entry point
│   └── index.css               ← Tailwind imports
│
├── index.html
├── package.json
├── tsconfig.json
├── tailwind.config.js
└── vite.config.ts
```

---

## Routing

```tsx
<Routes>
  <Route path="/" element={<AppShell />}>
    <Route index element={<Dashboard />} />
    <Route path="plan" element={<WeeklyPlan />} />
    <Route path="recipes" element={<RecipeLibrary />} />
    <Route path="recipes/:id" element={<RecipeDetail />} />
    <Route path="recipes/:id/cook" element={<CookingMode />} />
    <Route path="pantry" element={<PantryView />} />
    <Route path="shopping" element={<ShoppingList />} />
    <Route path="nutrition" element={<DailyView />} />
    <Route path="nutrition/weekly" element={<WeeklyView />} />
    <Route path="health" element={<CheckIn />} />
    <Route path="health/reviews" element={<Reviews />} />
    <Route path="discover" element={<DiscoveryFeed />} />
    <Route path="settings" element={<ProfileSettings />} />
    <Route path="settings/preferences" element={<PreferenceModelViewer />} />
    <Route path="settings/ai-usage" element={<AiUsage />} />
  </Route>
</Routes>
```

---

## Navigation

### Desktop (>768px)
Left sidebar with:
- Dashboard (home icon)
- Plan (calendar icon)
- Recipes (book icon)
- Pantry (box icon)
- Shopping (cart icon)
- Nutrition (bar chart icon)
- Health (heart icon)
- Discover (compass icon)
- Settings (gear icon) — bottom of sidebar

### Mobile (<768px)
Bottom navigation bar with 5 items:
- Dashboard
- Plan
- Recipes
- Pantry
- More → (slides up a menu with Shopping, Nutrition, Health, Discover, Settings)

---

## API Layer Pattern

```typescript
// api/client.ts
const API_BASE = '/api/v1';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.error, error.message, res.status);
  }
  return res.json();
}

// api/recipes.ts
export const recipesApi = {
  list: (params: RecipeFilterParams) =>
    request<Page<RecipeSummary>>(`/recipes?${toQuery(params)}`),
  get: (id: number) =>
    request<RecipeDetail>(`/recipes/${id}`),
  create: (data: CreateRecipeRequest) =>
    request<RecipeDetail>('/recipes', { method: 'POST', body: JSON.stringify(data) }),
  importFromUrl: (url: string) =>
    request<RecipeDetail>('/recipes/import', { method: 'POST', body: JSON.stringify({ url }) }),
  suggestChanges: (id: number, message: string) =>
    request<RecipeSuggestion>(`/recipes/${id}/suggest-changes`, {
      method: 'POST', body: JSON.stringify({ message })
    }),
};
```

## Hook Layer Pattern

```typescript
// hooks/useRecipes.ts
export function useRecipes(params: RecipeFilterParams) {
  return useQuery({
    queryKey: ['recipes', params],
    queryFn: () => recipesApi.list(params),
  });
}

export function useRecipe(id: number) {
  return useQuery({
    queryKey: ['recipe', id],
    queryFn: () => recipesApi.get(id),
  });
}

export function useCreateRecipe() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: recipesApi.create,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['recipes'] }),
  });
}

export function useImportRecipe() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (url: string) => recipesApi.importFromUrl(url),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['recipes'] }),
  });
}
```

---

## Key UI Patterns

### Optimistic Updates
For quick actions (check shopping item, mark meal as cooked), update the UI immediately and sync in the background. Roll back on failure.

```typescript
// Example: checking a shopping item
useMutation({
  mutationFn: ({ listId, itemId }) => shoppingApi.checkItem(listId, itemId, true),
  onMutate: async ({ itemId }) => {
    // Cancel outgoing refetches
    await queryClient.cancelQueries({ queryKey: ['shopping'] });
    // Optimistically update
    queryClient.setQueryData(['shopping'], (old) => ({
      ...old,
      items: old.items.map(i => i.id === itemId ? { ...i, checked: true } : i)
    }));
  },
  onError: (err, vars, context) => {
    // Roll back
    queryClient.setQueryData(['shopping'], context.previousData);
  },
});
```

### Loading States
Long AI operations (plan generation, recipe import) show a loading state with a message:
- "Generating your meal plan..." (10-30 seconds)
- "Importing recipe and calculating nutrition..." (5-15 seconds)

Use skeleton loaders for page-level data fetching. Spinners only for actions.

### Chat Sidebar
The AI chat sidebar (recipe suggestions, feedback) uses a simple message list pattern:

```typescript
interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  proposedChanges?: ProposedChanges;  // structured data alongside text
  timestamp: Date;
}
```

On mobile, this becomes a bottom sheet that slides up.

### Responsive Layout
- All grid layouts use CSS grid or flexbox with responsive breakpoints
- Recipe cards: 3-col on desktop, 2-col on tablet, 1-col on mobile
- Plan grid: full 7-day view on desktop, scrollable or daily view on mobile
- Pantry: table on desktop, card list on mobile

---

## State Management Summary

| State Type | Tool | Example |
|-----------|------|---------|
| Server data (recipes, plan, pantry) | TanStack Query | Recipe list, current plan |
| Server mutations | TanStack Query mutations | Create recipe, mark as cooked |
| UI-only state | Zustand | Sidebar open, selected date |
| Form state | React local state (useState) | Recipe creation form inputs |
| URL state | React Router | Current page, recipe ID |
