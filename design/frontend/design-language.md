# Design language — "D6 Hybrid"

The visual system for the MealPrep frontend. Synthesised from a five-direction
exploration (see [mockups/](mockups/), directions d1–d5) plus a structured critique;
proven across seven screens: week plan, today, plan generation, feedback routing,
groceries, recipe detail (`mockups/index.html`, routes `#d6-*`).

## Principles

1. **Type encodes who is speaking.** Grotesk = facts (times, names, numbers,
   data). Serif italic = the advisor's voice — the greeting, suggestion titles, AI
   reasoning, clarification questions. Anything the AI proposes reads as a considered
   note, not a system alert, and users get a subconscious cue for what is
   AI-generated and reversible. Never use the serif for data.
2. **Four colours, four meanings, none overloaded.**
   - Terracotta — *the system suggests / you act*: primary CTAs, suggestion markers, today.
   - Red — *danger only*: spoiled, affected-by-change, destructive. Never decoration.
   - Olive — done, on-track, confirmed.
   - Amber — time-sensitive: defrost, expiry-soon, behind-target, quality warnings.
3. **Symbol redundancy on every status.** State marks carry a glyph, not just a
   colour: ✓ eaten · ● cooked · ○ planned · ✕ affected. Colour-blind safe by default.
4. **Numbers are the heroes of data surfaces.** Oversized tabular numerals
   (Schibsted Grotesk 700) with muted "/ target" suffixes; segmented tick bars (not
   smooth fills) for progress — countable at a glance.
5. **AI interventions are cards with a consistent anatomy** (the "advisor card"):
   terracotta dot + uppercase label → serif title → detail (strikethrough → bold for
   before/after) → impact line → Dismiss/ghost + Accept/filled. Same shape for plan
   fixes, recipe suggestions, grocery substitutions — one muscle memory everywhere.
6. **Accessibility floor** (the five explored directions all failed this):
   labels ≥11px; muted text must pass WCAG AA 4.5:1 against its canvas; interactive
   targets ≥32px; clarification options are equal-weight (never pre-select an answer
   for the user).

## Tokens

### Colour

| Token | Hex | Use |
|-------|-----|-----|
| `bg` | `#faf6ec` | App canvas (warm paper) |
| `card` | `#fffdf6` | Raised surfaces |
| `ink` | `#262019` | Primary text |
| `muted` | `#6f6553` | Secondary text (≈5.6:1 on bg — AA) |
| `line` | `#e6decb` | Hairline rules, borders |
| `lineHi` | `#cfc4a9` | Emphasised borders, ghost-button borders |
| `terra` | `#c14e28` | Primary action / suggestion accent |
| `terraDark` | `#9c3c1d` | Terracotta text on light fills |
| `red` | `#b3261e` | Danger only |
| `olive` | `#5f7036` | Done / on-track |
| `amber` | `#9a6a1c` | Time-sensitive / behind |

Tints for chips: olive on `#eef0e2`; terracotta on `#f6e3d9`.

Dark mode (v1.1): derive from direction d5's charcoal (`#121217` base) keeping the
same four-colour semantics; not designed yet.

### Typography

| Role | Face | Notes |
|------|------|-------|
| Display + numerals | Schibsted Grotesk 700 | Tight tracking (−0.015em); tabular numerals |
| Body / UI | Instrument Sans 400/500/600 | 13–15px body, 11px uppercase labels (letter-spaced) |
| Advisor voice | Instrument Serif italic | Greetings, suggestion titles, AI reasoning, clarification questions — nothing else |

All via Google Fonts. Two weights of emphasis only; no 800+.

### Primitives

- **Stat band** — full-width card of N equal cells divided by hairlines: uppercase
  label / big numeral / sub-line. Used for: week stats, nutrition, grocery
  projection, candidate scores.
- **Advisor card** — see principle 5.
- **Segmented bar** — ~22 ticks, filled = olive (amber when behind), track = `line`.
- **Status marks** — see principle 3; planned-○ uses `#9c9077` (not `lineHi` — too faint).
- **Day-row grid** — week view is day-rows × meal-columns (calmer than 7-col cards);
  today row gets a 3px terracotta inset bar + card background.
- **Provenance chips** — small tinted pills tying data to its cause ("added by
  suggested fix", "swapped after feedback", "uses expiring spinach").
- **Order timeline** — five dots + connecting rules, filled to current state.
- **Confidence tiers (feedback)** — ✓ olive "routed" / ? amber "check me" /
  … terracotta "needs you"; reading order = decreasing machine confidence,
  increasing user involvement.

### Photography

The canvas is deliberately near-monochrome so **food photography is the colour of the
app**. Recipe imagery: 14px radius, `object-fit: cover`, warm `#e8dcc8` fallback
swatch. Never put text over photos.

## Exploration record

Five directions were built on identical data and critiqued
(`mockups/directions/d1–d5`): d1 "La Carte" (editorial ivory — source of the errata
concept + serif voice), d2 "Ledger" (dark mono — source of segmented bars), d3
"Gallery" (Swiss minimal — source of the skeleton, ranked best information design),
d4 "Harvest" (warm cards — source of canvas + terracotta, ranked best product fit),
d5 "Nocturne" (dark bento — reserved as future dark-mode reference). D6 is the
synthesis; the full critique is in the session record. Known unfinished business:
dark mode, mobile layouts, empty/loading states, motion.
