package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.api.dto.ExportFormat;
import com.example.mealprep.grocery.domain.entity.ShoppingList;
import com.example.mealprep.grocery.domain.entity.ShoppingListLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier-1 shopping-list renderer (grocery-01b). Pure transformation — no side effects, no DB.
 * Renders a {@link ShoppingList} (parent + lines) into one of {@link ExportFormat}'s four text
 * surfaces. Per lld/grocery.md lines 408-409 / 567-572.
 *
 * <p>{@code PRINTABLE_HTML} is the print-to-PDF surface (the frontend prints it); server-side PDF
 * is a deferred enhancement. An empty / staples-only list renders an empty / staples-only document
 * (it does not error — GG3 / GROC-04 edge). Package-private internal plumbing.
 */
@Component
class ShoppingListExporter {

  String render(ShoppingList list, ExportFormat format) {
    return switch (format) {
      case PRINTABLE_HTML -> html(list);
      case PLAIN_TEXT -> plainText(list);
      case MARKDOWN -> markdown(list);
      case CSV -> csv(list);
    };
  }

  private String html(ShoppingList list) {
    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        .append("<meta charset=\"utf-8\">\n")
        .append("<title>Shopping list</title>\n</head>\n<body>\n")
        .append("<h1>Shopping list</h1>\n");
    String total = totalLine(list);
    if (total != null) {
      sb.append("<p>").append(escapeHtml(total)).append("</p>\n");
    }
    List<ShoppingListLine> lines = list.getLines();
    if (lines == null || lines.isEmpty()) {
      sb.append("<p>No items.</p>\n");
    } else {
      sb.append("<ul>\n");
      for (ShoppingListLine line : lines) {
        sb.append("  <li>").append(escapeHtml(itemText(line))).append("</li>\n");
      }
      sb.append("</ul>\n");
    }
    sb.append("</body>\n</html>\n");
    return sb.toString();
  }

  private String plainText(ShoppingList list) {
    StringBuilder sb = new StringBuilder();
    sb.append("Shopping list\n");
    String total = totalLine(list);
    if (total != null) {
      sb.append(total).append('\n');
    }
    sb.append('\n');
    List<ShoppingListLine> lines = list.getLines();
    if (lines == null || lines.isEmpty()) {
      sb.append("(no items)\n");
    } else {
      for (ShoppingListLine line : lines) {
        sb.append("- ").append(itemText(line)).append('\n');
      }
    }
    return sb.toString();
  }

  private String markdown(ShoppingList list) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Shopping list\n\n");
    String total = totalLine(list);
    if (total != null) {
      sb.append("_").append(total).append("_\n\n");
    }
    List<ShoppingListLine> lines = list.getLines();
    if (lines == null || lines.isEmpty()) {
      sb.append("_(no items)_\n");
    } else {
      for (ShoppingListLine line : lines) {
        sb.append("- ").append(itemText(line)).append('\n');
      }
    }
    return sb.toString();
  }

  private String csv(ShoppingList list) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "ingredient_mapping_key,display_name,requested_quantity,requested_unit,"
            + "suggested_pack_size_g,suggested_pack_count,suggested_pack_unit,line_type,"
            + "quality_notes,estimated_line_pence\n");
    List<ShoppingListLine> lines = list.getLines();
    if (lines != null) {
      for (ShoppingListLine line : lines) {
        sb.append(csvCell(line.getIngredientMappingKey()))
            .append(',')
            .append(csvCell(line.getDisplayName()))
            .append(',')
            .append(csvCell(plainQty(line)))
            .append(',')
            .append(csvCell(line.getRequestedUnit()))
            .append(',')
            .append(csvCell(toStr(line.getSuggestedPackSizeG())))
            .append(',')
            .append(csvCell(toStr(line.getSuggestedPackCount())))
            .append(',')
            .append(csvCell(line.getSuggestedPackUnit()))
            .append(',')
            .append(csvCell(line.getLineType() == null ? null : line.getLineType().name()))
            .append(',')
            .append(csvCell(line.getQualityNotes()))
            .append(',')
            .append(csvCell(toStr(line.getEstimatedLinePence())))
            .append('\n');
      }
    }
    return sb.toString();
  }

  // ---- shared rendering helpers ---------------------------------------------------------------

  private static String itemText(ShoppingListLine line) {
    StringBuilder sb = new StringBuilder();
    sb.append(line.getDisplayName())
        .append(" — ")
        .append(plainQty(line))
        .append(' ')
        .append(line.getRequestedUnit());
    if (line.getSuggestedPackCount() != null && line.getSuggestedPackCount() > 0) {
      sb.append(" (buy ").append(line.getSuggestedPackCount());
      if (line.getSuggestedPackSizeG() != null) {
        sb.append(" × ").append(line.getSuggestedPackSizeG()).append(line.getSuggestedPackUnit());
      } else if (line.getSuggestedPackUnit() != null) {
        sb.append(' ').append(line.getSuggestedPackUnit());
      }
      sb.append(')');
    }
    if (line.getEstimatedLinePence() != null) {
      sb.append(" ~").append(pounds(line.getEstimatedLinePence()));
    }
    if (line.getQualityNotes() != null && !line.getQualityNotes().isBlank()) {
      sb.append(" [").append(line.getQualityNotes()).append(']');
    }
    return sb.toString();
  }

  private static String plainQty(ShoppingListLine line) {
    BigDecimal q = line.getRequestedQuantity();
    if (q == null) {
      return "0";
    }
    return q.stripTrailingZeros().toPlainString();
  }

  private static String totalLine(ShoppingList list) {
    if (list.getEstimatedTotalPence() == null) {
      return null;
    }
    return "Estimated total: " + pounds(list.getEstimatedTotalPence());
  }

  private static String pounds(int pence) {
    BigDecimal value =
        BigDecimal.valueOf(pence).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    return "£" + value.toPlainString();
  }

  private static String toStr(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  private static String escapeHtml(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String csvCell(String s) {
    if (s == null) {
      return "";
    }
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }
}
