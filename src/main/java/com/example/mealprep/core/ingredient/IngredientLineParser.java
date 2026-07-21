package com.example.mealprep.core.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a raw recipe ingredient line ("2 tbsp butter, melted") into its structured parts —
 * quantity, unit, a normalised ingredient name, and a preparation note — so the grocery / pantry
 * subsystem can aggregate demand, match pack sizes, and minimise the shopping list.
 *
 * <p>The datahive import stored each line verbatim (slugified) as the {@code
 * ingredient_mapping_key} with {@code quantity = 1} and an empty {@code unit}, so every
 * quantity/preparation variant of the same ingredient is a distinct key (≈74k keys over ≈133k
 * rows). Stripping the quantity, unit, and preparation collapses "1 tablespoon olive oil" and "2
 * tablespoons olive oil" to the same name ("olive oil") — the bulk of the de-duplication. Synonym
 * canonicalisation (plum tomatoes → tomato) is a separate refinement layered on the {@code name}
 * this parser produces.
 *
 * <p>Deterministic and side-effect-free: {@link #parse(String)} is a pure function. Lines it cannot
 * confidently parse return a low {@link Parsed#confidence()} so a downstream AI pass can target
 * only the residual rather than re-doing the whole pool.
 */
public final class IngredientLineParser {

  private IngredientLineParser() {}

  /**
   * Structured result. {@code name} is lower-cased + whitespace-collapsed; never null (may be "").
   */
  public record Parsed(
      String name, BigDecimal quantity, String unit, String preparation, BigDecimal confidence) {}

  // ---- Unit vocabulary: every spelling/abbreviation → a single canonical token ------------------

  private static final Map<String, String> UNITS =
      Map.ofEntries(
          Map.entry("cup", "cup"),
          Map.entry("cups", "cup"),
          Map.entry("c", "cup"),
          Map.entry("tablespoon", "tbsp"),
          Map.entry("tablespoons", "tbsp"),
          Map.entry("tbsp", "tbsp"),
          Map.entry("tbs", "tbsp"),
          Map.entry("tbsp.", "tbsp"),
          Map.entry("teaspoon", "tsp"),
          Map.entry("teaspoons", "tsp"),
          Map.entry("tsp", "tsp"),
          Map.entry("tsp.", "tsp"),
          Map.entry("ounce", "oz"),
          Map.entry("ounces", "oz"),
          Map.entry("oz", "oz"),
          Map.entry("pound", "lb"),
          Map.entry("pounds", "lb"),
          Map.entry("lb", "lb"),
          Map.entry("lbs", "lb"),
          Map.entry("gram", "g"),
          Map.entry("grams", "g"),
          Map.entry("g", "g"),
          Map.entry("kilogram", "kg"),
          Map.entry("kilograms", "kg"),
          Map.entry("kg", "kg"),
          Map.entry("milliliter", "ml"),
          Map.entry("milliliters", "ml"),
          Map.entry("millilitre", "ml"),
          Map.entry("ml", "ml"),
          Map.entry("liter", "l"),
          Map.entry("liters", "l"),
          Map.entry("litre", "l"),
          Map.entry("l", "l"),
          Map.entry("pint", "pint"),
          Map.entry("pints", "pint"),
          Map.entry("pt", "pint"),
          Map.entry("quart", "quart"),
          Map.entry("quarts", "quart"),
          Map.entry("qt", "quart"),
          Map.entry("gallon", "gallon"),
          Map.entry("gallons", "gallon"),
          Map.entry("gal", "gallon"),
          Map.entry("clove", "clove"),
          Map.entry("cloves", "clove"),
          Map.entry("can", "can"),
          Map.entry("cans", "can"),
          Map.entry("slice", "slice"),
          Map.entry("slices", "slice"),
          Map.entry("sprig", "sprig"),
          Map.entry("sprigs", "sprig"),
          Map.entry("stick", "stick"),
          Map.entry("sticks", "stick"),
          Map.entry("stalk", "stalk"),
          Map.entry("stalks", "stalk"),
          Map.entry("bunch", "bunch"),
          Map.entry("bunches", "bunch"),
          Map.entry("head", "head"),
          Map.entry("heads", "head"),
          Map.entry("package", "package"),
          Map.entry("packages", "package"),
          Map.entry("pkg", "package"),
          Map.entry("pinch", "pinch"),
          Map.entry("pinches", "pinch"));

  // Units written attached to the number ("3cm", "15oz") are split before lookup.
  private static final Set<String> PREP_WORDS =
      Set.of(
          "chopped",
          "minced",
          "diced",
          "sliced",
          "shredded",
          "grated",
          "crushed",
          "melted",
          "softened",
          "divided",
          "packed",
          "trimmed",
          "peeled",
          "finely",
          "freshly",
          "cooked",
          "drained",
          "rinsed",
          "beaten",
          "sifted",
          "cubed",
          "julienned",
          "halved",
          "quartered",
          "toasted",
          "roasted",
          "mashed",
          "pureed",
          "crumbled",
          "shaved",
          "pressed",
          "ground",
          "frozen",
          "thawed",
          "warmed",
          "cooled",
          "boiling");

  // Trailing free-text notes that aren't a real ingredient qualifier.
  private static final List<String> TRAILING_NOTES =
      List.of("to taste", "for serving", "for garnish", "optional", "as needed", "if desired");

  private static final Pattern LEADING_NOISE = Pattern.compile("^[\\s*\\-•·]+");
  private static final Pattern TRAILING_PRICE = Pattern.compile("\\$\\s*\\d+(?:[.,]\\d+)?\\s*$");
  private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
  // quantity: mixed (1 1/2), fraction (3/4), decimal (1.5), integer (2), optional range hi (2-3 →
  // 2)
  private static final Pattern QTY =
      Pattern.compile(
          "^(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+\\.\\d+|\\d+)(?:\\s*[-–]\\s*\\d+(?:\\.\\d+)?)?");
  private static final Map<String, String> UNICODE_FRACTIONS =
      Map.of("½", "1/2", "¼", "1/4", "¾", "3/4", "⅓", "1/3", "⅔", "2/3", "⅛", "1/8");

  /**
   * Parse a raw display line into {@link Parsed}. Never throws; an unparseable line yields a result
   * with the cleaned text as {@code name} and a low confidence.
   */
  public static Parsed parse(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return new Parsed("", null, null, null, BigDecimal.ZERO);
    }
    String s = displayName.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> e : UNICODE_FRACTIONS.entrySet()) {
      s = s.replace(e.getKey(), " " + e.getValue());
    }
    s = LEADING_NOISE.matcher(s).replaceAll("");
    s = TRAILING_PRICE.matcher(s).replaceAll("");
    s = s.replace("*", " ");
    s = PARENTHETICAL.matcher(s).replaceAll(" "); // drop "(8 oz)" pack hints for the name
    s = s.replaceAll("\\s+", " ").trim();

    // preparation: text after the first comma, plus any trailing free-text note.
    String preparation = null;
    int comma = s.indexOf(',');
    if (comma >= 0) {
      preparation = s.substring(comma + 1).trim();
      s = s.substring(0, comma).trim();
    }
    for (String note : TRAILING_NOTES) {
      if (s.endsWith(" " + note) || s.equals(note)) {
        preparation = preparation == null ? note : note + ", " + preparation;
        s = s.substring(0, s.length() - note.length()).trim();
      }
    }

    boolean qtyParsed = false;
    boolean unitParsed = false;
    BigDecimal quantity = null;
    String unit = null;

    Matcher m = QTY.matcher(s);
    if (m.find()) {
      quantity = toQuantity(m.group(1));
      qtyParsed = quantity != null;
      s = s.substring(m.end()).trim();
    }
    // a unit glued to the number ("3cm", "15oz") — peel a leading unit token off the remainder
    String[] tokens = s.isEmpty() ? new String[0] : s.split(" ");
    if (tokens.length > 0) {
      String first = tokens[0];
      String key = first.endsWith(".") ? first.substring(0, first.length() - 1) : first;
      String canonicalUnit = UNITS.get(key);
      if (canonicalUnit != null) {
        unit = canonicalUnit;
        unitParsed = true;
        s = s.substring(first.length()).trim();
        if (s.startsWith("of ")) {
          s = s.substring(3).trim();
        }
      }
    }

    // strip leading preparation adjectives into the prep note, leaving the bare ingredient name.
    StringBuilder leadingPrep = new StringBuilder();
    String[] nameTokens = s.isEmpty() ? new String[0] : s.split(" ");
    int start = 0;
    while (start < nameTokens.length && PREP_WORDS.contains(nameTokens[start])) {
      if (leadingPrep.length() > 0) {
        leadingPrep.append(' ');
      }
      leadingPrep.append(nameTokens[start]);
      start++;
    }
    // keep a trailing prep adjective too ("... minced", "... melted") when nothing followed a comma
    int end = nameTokens.length;
    while (end > start && PREP_WORDS.contains(nameTokens[end - 1])) {
      end--;
    }
    String name = String.join(" ", java.util.Arrays.asList(nameTokens).subList(start, end)).trim();
    if (leadingPrep.length() > 0) {
      preparation = preparation == null ? leadingPrep.toString() : leadingPrep + ", " + preparation;
    }

    BigDecimal confidence = confidence(qtyParsed, unitParsed, name);
    return new Parsed(name, quantity, unit, blankToNull(preparation), confidence);
  }

  private static BigDecimal toQuantity(String raw) {
    try {
      String t = raw.trim();
      if (t.contains(" ")) { // mixed number "1 1/2"
        String[] parts = t.split("\\s+");
        return new BigDecimal(parts[0]).add(fraction(parts[1]));
      }
      if (t.contains("/")) {
        return fraction(t);
      }
      return new BigDecimal(t);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static BigDecimal fraction(String f) {
    String[] p = f.split("/");
    return new BigDecimal(p[0]).divide(new BigDecimal(p[1]), 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal confidence(boolean qty, boolean unit, String name) {
    if (name.isEmpty()) {
      return BigDecimal.ZERO;
    }
    if (qty && unit) {
      return new BigDecimal("0.95");
    }
    if (qty || unit) {
      return new BigDecimal("0.80");
    }
    return new BigDecimal("0.50"); // bare name (e.g. "cinnamon", "salt and pepper") — still usable
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }
}
