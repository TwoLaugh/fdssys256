/**
 * The single money formatter for the admin console's two unit families
 * (admin.md §7 Q1): every AI figure is integer MICRO-PENCE except the status
 * card's aiMonthToDatePence (plain pence). A unit mix-up here is a 10⁶
 * display error, so both conversions live in this one module and are pinned
 * by the dev-mode self-check below (no test runner ships with the mock —
 * "no new deps" — so the pin runs at module load in dev instead).
 */

const GBP = new Intl.NumberFormat("en-GB", {
  style: "currency",
  currency: "GBP",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** £ from integer micro-pence: £1 = 100 000 000 µp. */
export function poundsFromMicroPence(microPence: number): string {
  return GBP.format(microPence / 100_000_000);
}

/** £ from plain pence (AdminStatusDto.aiMonthToDatePence only): £1 = 100p. */
export function poundsFromPence(pence: number): string {
  return GBP.format(pence / 100);
}

/** Sub-penny costs (single AI calls) need more precision than £0.00. */
export function microPenceDetail(microPence: number): string {
  if (microPence === 0) return "£0";
  const pounds = microPence / 100_000_000;
  if (pounds >= 0.01) return GBP.format(pounds);
  return `${(microPence / 1_000_000).toFixed(3)}p`;
}

if (import.meta.env.DEV) {
  // Pin the 10⁶/10² conversions (admin.md §8 delta 3).
  const pins: Array<[string, string]> = [
    [poundsFromMicroPence(100_000_000), "£1.00"],
    [poundsFromMicroPence(41_280_000), "£0.41"],
    [poundsFromPence(412), "£4.12"],
    [microPenceDetail(612_000), "0.612p"],
  ];
  for (const [actual, expected] of pins) {
    if (actual !== expected) {
      throw new Error(`money.ts conversion pin failed: ${actual} !== ${expected}`);
    }
  }
}
