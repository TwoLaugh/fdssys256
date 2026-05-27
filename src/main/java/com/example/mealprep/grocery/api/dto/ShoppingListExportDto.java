package com.example.mealprep.grocery.api.dto;

import java.util.UUID;

/** Rendered shopping-list export. Per lld/grocery.md line 409. */
public record ShoppingListExportDto(UUID shoppingListId, ExportFormat format, String content) {}
