package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import com.example.mealprep.grocery.domain.entity.ShoppingListLineType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ShoppingListExporter} (grocery-01b). Verifies each of the four export
 * surfaces (PRINTABLE_HTML, PLAIN_TEXT, MARKDOWN, CSV) renders the parent + lines correctly,
 * including: empty / staples-only edge, total-line gated on estimatedTotalPence, suggested-pack
 * rendering (g / count / unit branches), CSV escaping rules, HTML escaping, pence→pounds boundary,
 * stripTrailingZeros formatting, and quality-notes inclusion. Pure renderer test — no DB, no
 * collaborators.
 */
class ShoppingListExporterTest {

  private final ShoppingListExporter exporter = new ShoppingListExporter();

  private ShoppingList emptyList() {
    return ShoppingList.builder()
        .id(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .planId(UUID.randomUUID())
        .planGeneration(1)
        .estimatedTotalCurrency("GBP")
        .staleIngredientCount(0)
        .lines(new ArrayList<>())
        .build();
  }

  private ShoppingList list(Integer totalPence, ShoppingListLine... lines) {
    ShoppingList l = emptyList();
    l.setEstimatedTotalPence(totalPence);
    l.setLines(new ArrayList<>(List.of(lines)));
    return l;
  }

  private ShoppingListLine line(
      String key,
      String displayName,
      String qty,
      String unit,
      Integer packSizeG,
      Integer packCount,
      String packUnit,
      Integer estLinePence,
      String qualityNotes) {
    return ShoppingListLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .displayName(displayName)
        .requestedQuantity(qty == null ? null : new BigDecimal(qty))
        .requestedUnit(unit)
        .suggestedPackSizeG(packSizeG)
        .suggestedPackCount(packCount)
        .suggestedPackUnit(packUnit)
        .estimatedLinePence(estLinePence)
        .qualityNotes(qualityNotes)
        .lineType(ShoppingListLineType.PLANNED_DEMAND)
        .staleEstimate(false)
        .fulfilmentStatus(LineFulfilmentStatus.UNFILLED)
        .build();
  }

  // ============================== PRINTABLE_HTML ==============================

  @Test
  void html_emptyList_rendersNoItemsAndNoTotalLine() {
    String out = exporter.render(emptyList(), ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("<h1>Shopping list</h1>");
    assertThat(out).contains("<p>No items.</p>");
    assertThat(out).doesNotContain("Estimated total:");
    assertThat(out).startsWith("<!DOCTYPE html>");
  }

  @Test
  void html_renderTotalLine_whenTotalIsPresent() {
    ShoppingList l = list(1234, line("flour", "Flour", "1", "kg", 1000, 1, "g", 100, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("Estimated total: £12.34");
  }

  @Test
  void html_omitsTotalLine_whenTotalIsNull() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", 1000, 1, "g", 100, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).doesNotContain("Estimated total:");
  }

  @Test
  void html_renderLine_withPackSizeG_andLinePence() {
    ShoppingList l = list(80, line("flour", "Flour", "0.75", "kg", 1000, 1, "g", 80, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("Flour");
    assertThat(out).contains("0.75 kg");
    assertThat(out).contains("(buy 1 × 1000g)");
    assertThat(out).contains("~£0.80");
  }

  @Test
  void html_renderLine_packCountNoSizeG_usesPackUnit() {
    // Count-based pack (eggs): no packSizeG, just packUnit → renders "buy 1 items".
    ShoppingList l = list(null, line("eggs", "Eggs", "10", "items", null, 1, "items", null, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("(buy 1 items)");
  }

  @Test
  void html_renderLine_zeroPackCount_skipsPackBlurb() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", 1000, 0, "g", null, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).doesNotContain("(buy");
  }

  @Test
  void html_escapesAmpsAngleBracketsQuotes_inDisplayName() {
    ShoppingList l =
        list(null, line("k", "AT&T <bold> \"x\"", "1", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("AT&amp;T &lt;bold&gt; &quot;x&quot;");
    assertThat(out).doesNotContain("<bold>");
  }

  @Test
  void html_qualityNotesAppended_inBrackets() {
    ShoppingList l =
        list(null, line("eggs", "Eggs", "6", "items", null, null, null, null, "free-range"));
    String out = exporter.render(l, ExportFormat.PRINTABLE_HTML);
    assertThat(out).contains("[free-range]");
  }

  // ============================== PLAIN_TEXT ==============================

  @Test
  void plainText_emptyList_rendersNoItemsHeader() {
    String out = exporter.render(emptyList(), ExportFormat.PLAIN_TEXT);
    assertThat(out).startsWith("Shopping list\n");
    assertThat(out).contains("(no items)");
  }

  @Test
  void plainText_includesTotalLine_andLineEntries() {
    ShoppingList l = list(500, line("flour", "Flour", "1", "kg", 1000, 1, "g", 250, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).contains("Estimated total: £5.00");
    assertThat(out).contains("- Flour");
    assertThat(out).contains("~£2.50");
  }

  @Test
  void plainText_omitsTotalLine_whenTotalIsNull() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).doesNotContain("Estimated total:");
  }

  // ============================== MARKDOWN ==============================

  @Test
  void markdown_emptyList_rendersHeaderAndNoItemsPlaceholder() {
    String out = exporter.render(emptyList(), ExportFormat.MARKDOWN);
    assertThat(out).contains("# Shopping list");
    assertThat(out).contains("_(no items)_");
  }

  @Test
  void markdown_includesItalicTotalLine() {
    ShoppingList l = list(1234, line("flour", "Flour", "1", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.MARKDOWN);
    assertThat(out).contains("_Estimated total: £12.34_");
  }

  @Test
  void markdown_omitsTotalLine_whenTotalIsNull() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.MARKDOWN);
    assertThat(out).doesNotContain("Estimated total:");
  }

  // ============================== CSV ==============================

  @Test
  void csv_emptyList_headerOnly() {
    String out = exporter.render(emptyList(), ExportFormat.CSV);
    assertThat(out)
        .isEqualTo(
            "ingredient_mapping_key,display_name,requested_quantity,requested_unit,"
                + "suggested_pack_size_g,suggested_pack_count,suggested_pack_unit,line_type,"
                + "quality_notes,estimated_line_pence\n");
  }

  @Test
  void csv_lineWithAllFields_renders10ColumnsTerminatedByNewline() {
    ShoppingList l =
        list(100, line("flour", "Flour", "1.5", "kg", 1000, 2, "g", 200, "organic preferred"));
    String out = exporter.render(l, ExportFormat.CSV);
    String[] rows = out.split("\n");
    assertThat(rows).hasSize(2);
    String row = rows[1];
    assertThat(row).startsWith("flour,Flour,1.5,kg,1000,2,g,PLANNED_DEMAND,");
    assertThat(row).contains("organic preferred");
    assertThat(row).endsWith(",200");
  }

  @Test
  void csv_escapesCommas_quotes_newlinesInCells() {
    // Display name with comma → quoted; with quote → quote doubled; with newline → quoted.
    ShoppingListLine ln =
        ShoppingListLine.builder()
            .id(UUID.randomUUID())
            .ingredientMappingKey("k,1")
            .displayName("name with \"quote\" and ,")
            .requestedQuantity(new BigDecimal("1.0"))
            .requestedUnit("kg")
            .lineType(ShoppingListLineType.STAPLE_REPLENISHMENT)
            .qualityNotes("line1\nline2")
            .build();
    ShoppingList l = list(null, ln);
    String out = exporter.render(l, ExportFormat.CSV);

    assertThat(out).contains("\"k,1\""); // mapping key escaped (contains comma)
    assertThat(out)
        .contains("\"name with \"\"quote\"\" and ,\""); // both quote-doubling + comma escape
    assertThat(out).contains("\"line1\nline2\""); // newline → quoted
  }

  @Test
  void csv_nullCells_serializeAsEmptyString() {
    ShoppingListLine ln =
        ShoppingListLine.builder()
            .id(UUID.randomUUID())
            .ingredientMappingKey("flour")
            .displayName("Flour")
            .requestedQuantity(new BigDecimal("1"))
            .requestedUnit("kg")
            .lineType(null) // null line-type → empty cell
            .qualityNotes(null)
            .estimatedLinePence(null)
            .suggestedPackSizeG(null)
            .build();
    ShoppingList l = list(null, ln);
    String out = exporter.render(l, ExportFormat.CSV);

    String[] rows = out.split("\n");
    // Last 3 cells are line_type,quality_notes,estimated_line_pence → all empty:
    assertThat(rows[1]).endsWith(",,,");
  }

  // ============================== quantity formatting (plainQty / stripTrailingZeros)
  // ==============================

  @Test
  void quantity_stripsTrailingZeros() {
    ShoppingList l =
        list(null, line("flour", "Flour", "1.500", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).contains("1.5 kg"); // "1.500" → "1.5"
    assertThat(out).doesNotContain("1.500");
  }

  @Test
  void quantity_zeroDecimalCollapses_toPlainInteger() {
    ShoppingList l =
        list(null, line("eggs", "Eggs", "12.000", "items", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    // stripTrailingZeros on "12.000" → 1.2E+1; toPlainString → "1.2E+1"?
    // Actually for "12.000" → BigDecimal stripTrailingZeros gives 1.2E+1 (scale -1), so
    // toPlainString
    // → "12". Confirm "12 items" appears:
    assertThat(out).contains("12 items");
  }

  @Test
  void quantity_nullRequestedQuantity_renderedAsZero() {
    ShoppingListLine ln =
        ShoppingListLine.builder()
            .id(UUID.randomUUID())
            .ingredientMappingKey("flour")
            .displayName("Flour")
            .requestedQuantity(null) // null!
            .requestedUnit("kg")
            .lineType(ShoppingListLineType.PLANNED_DEMAND)
            .build();
    ShoppingList l = list(null, ln);
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).contains("- Flour — 0 kg");
  }

  // ============================== pence → pounds boundary ==============================

  @Test
  void poundsFormatting_singleDigitPence_isPaddedTwoDecimals() {
    // 5p → £0.05 (the divide(100, 2, HALF_UP)).
    ShoppingList l = list(5, line("flour", "Flour", "1", "kg", null, null, null, 5, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).contains("Estimated total: £0.05");
    assertThat(out).contains("~£0.05");
  }

  @Test
  void poundsFormatting_largeAmount_keepsDecimals() {
    ShoppingList l = list(123456, line("flour", "Flour", "1", "kg", null, null, null, 7890, null));
    String out = exporter.render(l, ExportFormat.MARKDOWN);
    assertThat(out).contains("£1234.56");
    assertThat(out).contains("~£78.90");
  }

  // ============================== quality notes optional ==============================

  @Test
  void qualityNotes_blank_notRendered() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", null, null, null, null, "  "));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).doesNotContain("[");
  }

  @Test
  void qualityNotes_null_notRendered() {
    ShoppingList l = list(null, line("flour", "Flour", "1", "kg", null, null, null, null, null));
    String out = exporter.render(l, ExportFormat.PLAIN_TEXT);
    assertThat(out).doesNotContain("[");
  }
}
