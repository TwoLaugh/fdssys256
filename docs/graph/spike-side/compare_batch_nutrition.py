# -*- coding: utf-8 -*-
"""G08 (boundary) -- spike-vs-engine nutrition comparison harness.

DESTINED FOR the spike repo (`culinary-graph-spike/corpus_expansion/compare_batch_nutrition.py`).
Delivered here (engine repo, docs/graph/spike-side/) because the spike checkout was READ-ONLY
input to this implementation session -- same delivery pattern as G05's export_mapping_seed.py.
When copied to corpus_expansion/ the default tolerance-file path (`../export/
comparison_tolerances.json`) applies unchanged.

THE STANDING 2.4x CONSUMED-BASIS LANDMINE TRIPWIRE (scoping ruling 4b): if the export's grams
basis and the seeded per-100g rows' basis ever disagree, the engine recompute silently diverges
from the spike's numbers. When a dish quarantines, suspect IN ORDER: (1) basis mismatch
export-vs-seed, (2) key->row mutation (user-correction flow -- check basis_note/updated_at on
the mapping row), (3) translation-table rot (G04 version mismatch), (4) fingerprint-dedup
masking changed content (ingest_report.json dedupSkipped > 0).

Per batch, diffs the engine's recomputed per-serving nutrition (GET /api/v1/recipes/{id})
against the spike's nutrition_expected.json, quarantines over-tolerance dishes (reversible:
POST .../archive), and writes <batchPath>/divergence_report.json.

Usage:
    python compare_batch_nutrition.py <batchPath> --base-url http://127.0.0.1:8080 \
        --auth <session-cookie-value> [--quarantine] [--pin-tolerances]

Auth: the value of the engine session cookie (AUTH_SESSION) for any authenticated user --
SYSTEM-recipe archive is open to any authenticated caller under the v1 admin-open policy; when
the engine grows an ADMIN role this must move with it.

Exit codes: 0 = all OK; 1 = any dish over-tolerance/quarantined/not-recomputed/not-comparable;
2 = harness error (join mismatch, missing artifact, frozen-tolerance violation, HTTP failure).

Tolerances (decision D5): loaded from the frozen artifact. First ever run must pass
--pin-tolerances: observed max deltas are computed on that batch and written with the 2% kcal /
5% micro ceilings (observed values may only tighten, never loosen, the ceilings), stamped and
frozen. Every later run refuses --pin-tolerances. Near-zero floor: when an expected micro is
< 1.0 unit, an ABSOLUTE delta <= 0.05 * unit gates instead (relative % on near-zero micros is
noise); a non-zero engine value on a zero-expected key is over-tolerance (fabrication
detector). Missing/extra micro keys are over-tolerance (vocabulary-rot detector).

Stdlib only (urllib). No engine code, no spike imports -- pure boundary artifact.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

REPORT_HEADER = (
    "G08 divergence report -- standing 2.4x consumed-basis landmine tripwire "
    "(scoping 4b): on quarantine suspect (1) basis mismatch export-vs-seed, "
    "(2) mapping-row mutation, (3) translation-table rot, (4) dedup-masked content change."
)

# Ceilings (D5): pinned observed values may tighten these but never loosen them.
KCAL_TOL_CEILING = 0.02
MICRO_TOL_CEILING = 0.05
NEAR_ZERO_UNIT = 1.0
NEAR_ZERO_ABS_FLOOR = 0.05

STATUS_OK = "OK"
STATUS_QUARANTINED = "QUARANTINED"
STATUS_NOT_RECOMPUTED = "NOT_RECOMPUTED"
STATUS_NOT_COMPARABLE = "NOT_COMPARABLE"

MACRO_KEYS = ("proteinG", "carbsG", "fatG", "fibreG")


class HarnessError(Exception):
    """Exit-2 class: the run itself is invalid, no dish verdicts are trustworthy."""


# ---------------------------------------------------------------------------
# pure diff logic (unit-tested offline)
# ---------------------------------------------------------------------------

def rel_delta(engine, expected):
    """|engine - expected| / expected; expected == 0 handled by caller (absolute path)."""
    return abs(float(engine) - float(expected)) / abs(float(expected))


def micro_within(engine, expected, micro_tol):
    """Near-zero absolute floor: expected < 1.0 unit -> absolute delta <= 0.05 unit.
    Zero-expected: engine must be zero too (fabrication detector). Else relative."""
    engine = float(engine)
    expected = float(expected)
    if expected == 0.0:
        return engine == 0.0
    if abs(expected) < NEAR_ZERO_UNIT:
        return abs(engine - expected) <= NEAR_ZERO_ABS_FLOOR
    return rel_delta(engine, expected) <= micro_tol


def diff_dish(engine_nps, expected_entry, tolerances):
    """Diff one dish. Returns dict {kcalDelta, worstMicro, worstMicroDelta, macroDeltas,
    over: bool, reasons: [..]}. engine_nps = nutritionPerServing object from RecipeDto;
    expected_entry = the nutrition_expected.json per-fingerprint object."""
    kcal_tol = tolerances["kcal_rel_tol"]
    micro_tol = tolerances["micro_rel_tol"]
    reasons = []

    exp_kcal = float(expected_entry["calories"])
    eng_kcal = float(engine_nps.get("calories", 0))
    if exp_kcal == 0.0:
        kcal_delta = 0.0 if eng_kcal == 0.0 else float("inf")
    else:
        kcal_delta = rel_delta(eng_kcal, exp_kcal)
    if kcal_delta > kcal_tol:
        reasons.append(
            "kcal delta %.4f > %.4f (engine %s vs expected %s)"
            % (kcal_delta, kcal_tol, eng_kcal, exp_kcal))

    # Macros: informational in v1 (kcal is the gate) -- printed for eyes.
    macro_deltas = {}
    for key in MACRO_KEYS:
        exp = expected_entry.get(key)
        eng = engine_nps.get(key)
        if exp is None or eng is None:
            continue
        exp = float(exp)
        macro_deltas[key] = (abs(float(eng) - exp) / exp) if exp else abs(float(eng))

    exp_micros = expected_entry.get("micros") or {}
    eng_micros = engine_nps.get("micros") or {}
    worst_micro, worst_delta = None, -1.0
    for key, exp_val in sorted(exp_micros.items()):
        if key not in eng_micros:
            reasons.append("micro key missing engine-side: %s (vocabulary rot?)" % key)
            continue
        eng_val = eng_micros[key]
        if not micro_within(eng_val, exp_val, micro_tol):
            reasons.append(
                "micro %s out of tolerance (engine %s vs expected %s)"
                % (key, eng_val, exp_val))
        exp_f = float(exp_val)
        delta = rel_delta(eng_val, exp_f) if exp_f else (0.0 if float(eng_val) == 0.0 else float("inf"))
        if delta > worst_delta:
            worst_micro, worst_delta = key, delta
    for key in sorted(eng_micros):
        if key not in exp_micros:
            reasons.append("micro key present engine-side only: %s (vocabulary rot?)" % key)

    return {
        "kcalDelta": kcal_delta,
        "macroDeltas": macro_deltas,
        "worstMicro": worst_micro,
        "worstMicroDelta": worst_delta if worst_micro is not None else None,
        "over": bool(reasons),
        "reasons": reasons,
    }


def observed_maxima(diffs):
    """Max observed kcal / micro deltas across in-tolerance dishes -- the pinned values."""
    kcal = 0.0
    micro = 0.0
    for d in diffs:
        if d["kcalDelta"] not in (None, float("inf")):
            kcal = max(kcal, d["kcalDelta"])
        if d["worstMicroDelta"] not in (None, float("inf")):
            micro = max(micro, d["worstMicroDelta"])
    return kcal, micro


# ---------------------------------------------------------------------------
# tolerance pin/freeze lifecycle (D5)
# ---------------------------------------------------------------------------

def load_or_pin_tolerances(path, pin_requested, batch_id, spike_commit, corpus_fp, diffs=None):
    """Load the frozen tolerance artifact, or (first run, --pin-tolerances) write it.
    Returns the tolerance dict actually used for gating. In pin mode gating uses the
    CEILINGS (there is nothing frozen yet); the observed values are recorded."""
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            frozen = json.load(fh)
        if pin_requested:
            raise HarnessError(
                "tolerances already pinned+frozen at %s -- refusing --pin-tolerances "
                "(changing tolerances is a reviewed edit to a frozen artifact)" % path)
        if not frozen.get("frozen"):
            raise HarnessError("tolerance artifact %s exists but is not frozen" % path)
        return frozen
    if not pin_requested:
        raise HarnessError(
            "no tolerance artifact at %s -- the FIRST run must pass --pin-tolerances "
            "to pin from this batch's observed rounding (D5)" % path)
    observed_kcal, observed_micro = observed_maxima(diffs or [])
    pinned = {
        "schema": "graph-comparison-tolerances/1",
        # Observed may only TIGHTEN the ceilings, never loosen them.
        "kcal_rel_tol": min(max(observed_kcal, 0.0), KCAL_TOL_CEILING),
        "micro_rel_tol": min(max(observed_micro, 0.0), MICRO_TOL_CEILING),
        "kcal_ceiling": KCAL_TOL_CEILING,
        "micro_ceiling": MICRO_TOL_CEILING,
        "near_zero_unit": NEAR_ZERO_UNIT,
        "near_zero_abs_floor": NEAR_ZERO_ABS_FLOOR,
        "observed": {"kcal_max_delta": observed_kcal, "micro_max_delta": observed_micro},
        "batch_id": batch_id,
        "spike_commit": spike_commit,
        "corpus_fingerprint": corpus_fp,
        "frozen": True,
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(pinned, fh, indent=1, sort_keys=True)
        fh.write("\n")
    return pinned


CEILING_TOLERANCES = {"kcal_rel_tol": KCAL_TOL_CEILING, "micro_rel_tol": MICRO_TOL_CEILING}


# ---------------------------------------------------------------------------
# engine REST client (urllib; cookie auth)
# ---------------------------------------------------------------------------

class EngineClient(object):
    def __init__(self, base_url, cookie, cookie_name="AUTH_SESSION"):
        self.base_url = base_url.rstrip("/")
        self.cookie = "%s=%s" % (cookie_name, cookie)

    def _request(self, method, path, body=None):
        req = urllib.request.Request(
            self.base_url + path, method=method,
            data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Cookie", self.cookie)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as resp:
                payload = resp.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as e:
            raise HarnessError("%s %s -> HTTP %d" % (method, path, e.code))
        except urllib.error.URLError as e:
            raise HarnessError("%s %s -> %s" % (method, path, e.reason))

    def get_recipe(self, recipe_id):
        return self._request("GET", "/api/v1/recipes/%s" % recipe_id)

    def archive(self, recipe_id):
        return self._request("POST", "/api/v1/recipes/%s/archive" % recipe_id)


# ---------------------------------------------------------------------------
# batch run
# ---------------------------------------------------------------------------

def join_batch(expected_by_fp, ingest_report):
    """Approved-set join: every ingested fp needs an expected entry and vice versa."""
    ingested = {e["fp"]: e for e in ingest_report.get("recipeIds", [])}
    missing_expected = sorted(set(ingested) - set(expected_by_fp))
    missing_ingested = sorted(set(expected_by_fp) - set(ingested))
    if missing_expected or missing_ingested:
        raise HarnessError(
            "fingerprint join mismatch: ingested-without-expected=%s expected-without-ingested=%s"
            % (missing_expected, missing_ingested))
    return ingested


def run(batch_path, client, quarantine, pin_tolerances, tolerance_path):
    expected_file = os.path.join(batch_path, "nutrition_expected.json")
    report_file = os.path.join(batch_path, "ingest_report.json")
    for p in (expected_file, report_file):
        if not os.path.exists(p):
            raise HarnessError("missing artifact: %s" % p)
    with open(expected_file, "r", encoding="utf-8") as fh:
        expected_doc = json.load(fh)
    with open(report_file, "r", encoding="utf-8") as fh:
        ingest_report = json.load(fh)

    expected_by_fp = expected_doc.get("dishes") or {
        k: v for k, v in expected_doc.items() if not k.startswith("_")}
    ingested = join_batch(expected_by_fp, ingest_report)

    # Pass 1: fetch + diff every dish against the ceilings (pin mode) or frozen tolerances.
    if os.path.exists(tolerance_path) or not pin_tolerances:
        tolerances = load_or_pin_tolerances(
            tolerance_path, pin_tolerances, None, None, None)
        pin_mode = False
    else:
        tolerances = dict(CEILING_TOLERANCES)
        pin_mode = True

    per_dish = []
    diffs_for_pin = []
    for fp in sorted(ingested):
        entry = ingested[fp]
        recipe_id = entry["recipeId"]
        recipe = client.get_recipe(recipe_id)
        current_version = recipe.get("currentVersion")
        body = recipe.get("currentVersionBody") or {}
        nps = body.get("nutritionPerServing")
        row = {"fp": fp, "recipeId": recipe_id, "kcalDelta": None,
               "worstMicro": None, "worstMicroDelta": None, "status": STATUS_OK,
               "reasons": []}
        if current_version not in (None, 1):
            # Adapted/branched since import -- diffing the adapted body would be a false
            # delta; report, do not quarantine.
            row["status"] = STATUS_NOT_COMPARABLE
            row["reasons"] = ["currentVersion=%s != 1" % current_version]
        elif not nps:
            row["status"] = STATUS_NOT_RECOMPUTED
            row["reasons"] = ["nutritionPerServing is null -- dish failed G07 recompute"]
        else:
            d = diff_dish(nps, expected_by_fp[fp], tolerances)
            row.update({k: d[k] for k in ("kcalDelta", "worstMicro", "worstMicroDelta")})
            row["macroDeltas"] = d["macroDeltas"]
            row["reasons"] = d["reasons"]
            if d["over"]:
                row["status"] = STATUS_QUARANTINED  # pending lever below / dry-run naming
            diffs_for_pin.append(d)
        per_dish.append(row)

    if pin_mode:
        tolerances = load_or_pin_tolerances(
            tolerance_path, True,
            ingest_report.get("batchId"),
            expected_doc.get("_meta", {}).get("spike_commit"),
            expected_doc.get("_meta", {}).get("corpus_fingerprint"),
            diffs=diffs_for_pin)

    # Pass 2: quarantine lever (reversible archive) -- only with --quarantine.
    for row in per_dish:
        if row["status"] in (STATUS_QUARANTINED, STATUS_NOT_RECOMPUTED):
            if quarantine:
                client.archive(row["recipeId"])
                verify = client.get_recipe(row["recipeId"])
                if verify.get("archivedAt") is None:
                    raise HarnessError(
                        "archive verify failed for %s: archivedAt still null" % row["recipeId"])
                row["archived"] = True
            else:
                row["archived"] = False  # dry run: named, not archived

    ok = sum(1 for r in per_dish if r["status"] == STATUS_OK)
    flagged = len(per_dish) - ok
    exit_code = 0 if flagged == 0 else 1
    report = {
        "header": REPORT_HEADER,
        "batch_id": ingest_report.get("batchId"),
        "tolerances_version": {
            "kcal_rel_tol": tolerances["kcal_rel_tol"],
            "micro_rel_tol": tolerances["micro_rel_tol"],
            "source": tolerance_path,
        },
        "dry_run": not quarantine,
        "per_dish": per_dish,
        "summary": {"ok": ok, "quarantined": flagged},
        "exit_code": exit_code,
    }
    out_path = os.path.join(batch_path, "divergence_report.json")
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1, sort_keys=True)
        fh.write("\n")
    return report


def print_table(report):
    print(report["header"])
    print("batch %s  (dry_run=%s)" % (report["batch_id"], report["dry_run"]))
    fmt = "%-16s %-38s %-10s %-12s %s"
    print(fmt % ("fp", "recipeId", "kcalDelta", "status", "worstMicro"))
    for row in report["per_dish"]:
        kcal = ("%.4f" % row["kcalDelta"]) if row["kcalDelta"] is not None else "-"
        worst = ("%s=%.4f" % (row["worstMicro"], row["worstMicroDelta"])
                 if row["worstMicro"] else "-")
        print(fmt % (row["fp"][:16], row["recipeId"], kcal, row["status"], worst))
        for reason in row["reasons"]:
            print("    ! %s" % reason)
    print("summary: ok=%d quarantined=%d exit=%d"
          % (report["summary"]["ok"], report["summary"]["quarantined"], report["exit_code"]))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("batchPath")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--auth", required=True,
                        help="session cookie VALUE (AUTH_SESSION)")
    parser.add_argument("--cookie-name", default="AUTH_SESSION")
    parser.add_argument("--quarantine", action="store_true",
                        help="archive over-tolerance dishes (default: dry run, report only)")
    parser.add_argument("--pin-tolerances", action="store_true",
                        help="first-batch-only: pin+freeze observed tolerances (D5)")
    parser.add_argument("--tolerance-file",
                        default=os.path.join(HERE, "..", "export", "comparison_tolerances.json"))
    args = parser.parse_args(argv)

    client = EngineClient(args.base_url, args.auth, args.cookie_name)
    try:
        report = run(os.path.abspath(args.batchPath), client, args.quarantine,
                     args.pin_tolerances, os.path.abspath(args.tolerance_file))
    except HarnessError as e:
        print("HARNESS ERROR: %s" % e, file=sys.stderr)
        return 2
    print_table(report)
    return report["exit_code"]


if __name__ == "__main__":
    sys.exit(main())
