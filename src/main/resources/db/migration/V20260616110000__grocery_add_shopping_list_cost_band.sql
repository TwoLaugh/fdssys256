-- Grocery — cost-variance band on shopping_lists (tickets/frontend-gaps/grocery-cost-variance.md).
-- The HLD's "£47 ± £8" band needs list-level bounds; the per-line aggregates already carry
-- min/max unit pence, so step 6 of the calculator sums them into these two columns.
-- Both nullable: null when no price data at all (cold start — same rule as estimated_total_pence).

ALTER TABLE shopping_lists
    ADD COLUMN estimated_total_min_pence integer,
    ADD COLUMN estimated_total_max_pence integer;
