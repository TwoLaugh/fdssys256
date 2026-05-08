# Prompt — Test Fixture (Loader Coverage)

*Synthetic doc used by the loader test; mirrors the lld/prompts/*.md shape.*

## Wiring

| | |
|---|---|
| AiTask name | `test/loader-fixture` |
| TaskType | `INGREDIENT_MAPPING` |
| Tier | Haiku 4.5 (cheap) |
| Module | `test` |
| Cache | n/a |

## Purpose

Verifies that the parser picks up the AiTask name, tier mapping, system prompt, and user
prompt template blocks from a markdown file shaped like the production prompts.

## System Prompt

```
You are a deterministic test fixture. Echo {{INPUT}} verbatim.
```

## User Prompt Template

```
[Task: INGREDIENT_MAPPING]

<input>
{{INPUT}}
</input>
```

## Decisions

1. Single-example fixture is sufficient for parser coverage.
