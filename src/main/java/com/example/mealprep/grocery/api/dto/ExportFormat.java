package com.example.mealprep.grocery.api.dto;

/** Render targets for {@code ShoppingListService.export}. Per lld/grocery.md line 408. */
public enum ExportFormat {
  PRINTABLE_HTML,
  PLAIN_TEXT,
  MARKDOWN,
  CSV
}
