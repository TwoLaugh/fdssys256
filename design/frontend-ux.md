# Frontend & UX

## Platform Approach

**Web app (responsive) as the primary target**, working well on both mobile and desktop.

Options for delivery:
- **Progressive Web App (PWA)** — install to home screen on phone, works offline for viewing plans/recipes, feels native-ish. No app store needed.
- **Responsive web** — same thing but without the install/offline features. Simpler to build.
- **Desktop app** — Electron wrapper around the web app if wanted later. Low priority.

**Recommendation**: Build as a responsive web app first, add PWA features (installable, offline recipe viewing) when core features are stable. Avoids React Native / Flutter complexity while still being usable on phone.

## Tech Choice

The frontend needs:
- Dynamic data (plans, recipes, pantry update in real time)
- Multiple views/pages (dashboard, recipes, pantry, settings, etc.)
- Chat-like interactions (feedback, "suggest changes" sidebar)
- Mobile-friendly layouts

**React** is the pragmatic choice:
- Huge ecosystem, easy to find components for calendars, charts, chat UIs
- Next.js gives server-side rendering + API routes if we want to consolidate
- OR keep it as a standalone React SPA that talks to the Spring Boot backend

**Alternative**: If you prefer staying in the Java ecosystem, Thymeleaf + HTMX could work for simpler pages, but the chat/interactive elements would be awkward.

## Core Views

### 1. Dashboard (Home)
The daily view — what you see when you open the app.
- Today's meals (breakfast, lunch, dinner, snacks) with quick actions
- Nutrition summary for today (progress bars: calories, protein, carbs, fat)
- Upcoming alerts ("Mince expires tomorrow", "Start marinating at 6pm")
- Quick actions: "I ate this" / "Skip" / "Swap"

### 2. Weekly Plan
Calendar-style grid view of the full week.
- Drag-and-drop to rearrange meals (optional, nice-to-have)
- Colour coding: cooked (green), upcoming (neutral), skipped (grey)
- Tap any meal → recipe detail
- "Generate new plan" button
- Override slots: "I want X on this day"

### 3. Recipe Library
Browse/search all recipes.
- Card grid layout with filters (source, cuisine, rating, dietary tags)
- Each card: name, photo (AI-generated or from import), rating, prep time, calories
- Add recipe: manual / import URL / "AI suggest something"
- Tap → full recipe view with history, notes, feedback, "suggest changes" sidebar

### 4. Recipe Detail + Sidebar Chat
Full recipe page with an AI chat sidebar.
- Recipe content on the left (ingredients, steps, nutrition, version history)
- Chat sidebar on the right: "This was too salty" / "Can you make this lower carb?" / "Suggest a variation"
- AI responds with proposed changes, user approves → new version created
- On mobile: chat is a bottom sheet that slides up

### 5. Pantry
Inventory view of what's in the house.
- Grouped by category (proteins, dairy, veg, grains, etc.)
- Each item: name, quantity, expiry (highlighted if soon)
- Quick add (manual or from shopping list)
- "Use up" suggestions: "You have 200g spinach expiring Tuesday → [recipes that use spinach]"

### 6. Shopping List
Generated from the meal plan minus pantry.
- Grouped by store section
- Checkboxes for manual shopping
- Estimated total cost
- "Order from Tesco" button → triggers automation, opens review before checkout

### 7. Nutrition Tracker
Daily/weekly nutrition view.
- Daily: pre-populated from the plan, tap to confirm/skip/adjust
- Macro progress bars (calories, protein, carbs, fat)
- Weekly trend charts
- Deviation notes (auto-flagged if way off target)

### 8. Settings / Profile
- Dietary constraints, allergies, intolerances
- Nutrition goals (calories, macros)
- Preferences (cuisines, cooking time, skill level, household size)
- Variety settings (new vs repeat ratio)
- Tesco account connection

## Mobile Considerations
- Bottom navigation bar (Dashboard, Plan, Recipes, Pantry, More)
- Swipe gestures for common actions (swipe meal to skip/mark as eaten)
- Large tap targets for cooking mode (greasy fingers)
- Recipe "cook mode": step-by-step with large text, screen stays on

## Design Aesthetic
TBD — but clean, minimal, food-forward. Think less "enterprise dashboard", more "a nice cookbook app that happens to be smart."
