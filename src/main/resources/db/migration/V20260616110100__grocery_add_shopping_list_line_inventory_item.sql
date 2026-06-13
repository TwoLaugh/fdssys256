-- Grocery — persist the provisions inventory link created by mark-bought
-- (tickets/frontend-gaps/grocery-undo-pantry-reversal.md). Soft FK to provision_inventory.id;
-- populated when the mark-bought import added/merged an inventory row, cleared again on undo.
-- Undo uses it to drive the best-effort compensating reversal via the provisions public API.

ALTER TABLE shopping_list_lines
    ADD COLUMN inventory_item_id uuid;
