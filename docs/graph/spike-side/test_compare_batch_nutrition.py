# -*- coding: utf-8 -*-
"""Offline tests for compare_batch_nutrition.py (G08). No live engine needed.

DESTINED FOR `culinary-graph-spike/corpus_expansion/` alongside the harness.
Covers the ticket's acceptance fixtures: in-tolerance OK; kcal +3% flagged; missing
engine-side micro key flagged; near-zero micro under the absolute floor OK;
zero-expected fabrication detector; not-recomputed flagged; tolerance pin/freeze
lifecycle (first run pins, second refuses); dry run performs zero archive calls;
quarantine archives + verifies; join mismatch = harness error; currentVersion != 1
= NOT_COMPARABLE (no quarantine); divergence_report.json schema fields; exit codes.

Run: python test_compare_batch_nutrition.py
"""
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import compare_batch_nutrition as CBN  # noqa: E402

FAILURES = []


def check(name, cond, detail=""):
    tag = "ok" if cond else "FAIL"
    print("  [%s] %s%s" % (tag, name, (" -- " + detail) if (detail and not cond) else ""))
    if not cond:
        FAILURES.append(name)


TOL = {"kcal_rel_tol": 0.02, "micro_rel_tol": 0.05}


def expected_entry(kcal=500, micros=None):
    return {"calories": kcal, "proteinG": 30, "carbsG": 40, "fatG": 20, "fibreG": 5,
            "micros": micros if micros is not None else {"iron_mg": 4.0}}


def engine_nps(kcal=500, micros=None):
    return {"calories": kcal, "proteinG": 30, "carbsG": 40, "fatG": 20, "fibreG": 5,
            "micros": micros if micros is not None else {"iron_mg": 4.0}}


def test_diff_logic():
    print("diff logic:")
    d = CBN.diff_dish(engine_nps(), expected_entry(), TOL)
    check("identical dish in tolerance", not d["over"], str(d["reasons"]))

    d = CBN.diff_dish(engine_nps(kcal=515), expected_entry(kcal=500), TOL)
    check("kcal +3% flagged", d["over"] and any("kcal" in r for r in d["reasons"]))

    d = CBN.diff_dish(engine_nps(kcal=505), expected_entry(kcal=500), TOL)
    check("kcal +1% ok", not d["over"])

    d = CBN.diff_dish(engine_nps(micros={}), expected_entry(micros={"iron_mg": 4.0}), TOL)
    check("micro key missing engine-side flagged",
          d["over"] and any("missing engine-side" in r for r in d["reasons"]))

    d = CBN.diff_dish(engine_nps(micros={"iron_mg": 4.0, "zinc_mg": 1.1}),
                      expected_entry(micros={"iron_mg": 4.0}), TOL)
    check("micro key extra engine-side flagged",
          d["over"] and any("engine-side only" in r for r in d["reasons"]))

    # Near-zero absolute floor: expected 0.02, engine 0.03 -> |delta| 0.01 <= 0.05 -> OK
    d = CBN.diff_dish(engine_nps(micros={"vitamin_d_mcg": 0.03}),
                      expected_entry(micros={"vitamin_d_mcg": 0.02}), TOL)
    check("near-zero micro under absolute floor ok", not d["over"], str(d["reasons"]))

    # Zero-expected fabrication detector: engine non-zero on zero-expected key
    d = CBN.diff_dish(engine_nps(micros={"vitamin_d_mcg": 0.2}),
                      expected_entry(micros={"vitamin_d_mcg": 0.0}), TOL)
    check("zero-expected non-zero engine flagged (fabrication)", d["over"])
    d = CBN.diff_dish(engine_nps(micros={"vitamin_d_mcg": 0.0}),
                      expected_entry(micros={"vitamin_d_mcg": 0.0}), TOL)
    check("zero-expected zero engine ok", not d["over"])

    # Micro relative gate at scale: expected 100 mg, engine 106 -> 6% > 5% flagged
    d = CBN.diff_dish(engine_nps(micros={"calcium_mg": 106.0}),
                      expected_entry(micros={"calcium_mg": 100.0}), TOL)
    check("micro +6% flagged", d["over"])
    d = CBN.diff_dish(engine_nps(micros={"calcium_mg": 104.0}),
                      expected_entry(micros={"calcium_mg": 100.0}), TOL)
    check("micro +4% ok", not d["over"])


class FakeClient(object):
    """Records calls; serves canned recipes."""

    def __init__(self, recipes):
        self.recipes = recipes
        self.archived = []

    def get_recipe(self, recipe_id):
        return json.loads(json.dumps(self.recipes[recipe_id]))

    def archive(self, recipe_id):
        self.archived.append(recipe_id)
        self.recipes[recipe_id]["archivedAt"] = "2026-07-21T12:00:00Z"


def batch_dir(tmp, fps_to_expected, recipe_ids, statuses=None):
    os.makedirs(tmp, exist_ok=True)
    with open(os.path.join(tmp, "nutrition_expected.json"), "w") as fh:
        json.dump({"dishes": fps_to_expected,
                   "_meta": {"spike_commit": "28599f0",
                             "corpus_fingerprint": "c81a2e87dacf339f"}}, fh)
    report = {"batchId": "batch-t", "recipeIds": [
        {"fp": fp, "recipeId": recipe_ids[fp], "versionId": "v-" + fp[:4],
         "nutritionStatus": (statuses or {}).get(fp, "CALCULATED")}
        for fp in fps_to_expected]}
    with open(os.path.join(tmp, "ingest_report.json"), "w") as fh:
        json.dump(report, fh)


def recipe(nps, current_version=1, archived_at=None):
    return {"currentVersion": current_version, "archivedAt": archived_at,
            "currentVersionBody": {"nutritionPerServing": nps}}


def test_run_lifecycle():
    print("run lifecycle:")
    root = tempfile.mkdtemp(prefix="g08-test-")
    try:
        tol_path = os.path.join(root, "comparison_tolerances.json")

        # -- first run without pin: harness error (exit-2 class)
        b1 = os.path.join(root, "b1")
        fps = {"a" * 64: expected_entry(), "b" * 64: expected_entry(kcal=400)}
        ids = {"a" * 64: "rid-a", "b" * 64: "rid-b"}
        batch_dir(b1, fps, ids)
        client = FakeClient({"rid-a": recipe(engine_nps()),
                             "rid-b": recipe(engine_nps(kcal=401))})
        try:
            CBN.run(b1, client, False, False, tol_path)
            check("first run without --pin-tolerances refused", False)
        except CBN.HarnessError:
            check("first run without --pin-tolerances refused", True)

        # -- first run WITH pin: writes frozen artifact, all OK, exit 0
        report = CBN.run(b1, client, False, True, tol_path)
        check("pin run exit 0", report["exit_code"] == 0, str(report))
        check("tolerance file written+frozen",
              os.path.exists(tol_path) and json.load(open(tol_path))["frozen"] is True)
        frozen = json.load(open(tol_path))
        check("pinned kcal tol <= ceiling", frozen["kcal_rel_tol"] <= 0.02)
        check("observed recorded", "observed" in frozen)

        # -- second run with pin: refused
        try:
            CBN.run(b1, client, False, True, tol_path)
            check("second --pin-tolerances refused (frozen)", False)
        except CBN.HarnessError:
            check("second --pin-tolerances refused (frozen)", True)

        # -- dry run with an over-tolerance dish: named, ZERO archive calls
        b2 = os.path.join(root, "b2")
        batch_dir(b2, fps, ids)
        client2 = FakeClient({"rid-a": recipe(engine_nps()),
                              "rid-b": recipe(engine_nps(kcal=520))})  # +30% vs 400
        report = CBN.run(b2, client2, False, False, tol_path)
        check("dry run exit 1 on over-tolerance", report["exit_code"] == 1)
        check("dry run performs zero archive calls", client2.archived == [])
        flagged = [r for r in report["per_dish"] if r["status"] == CBN.STATUS_QUARANTINED]
        check("over-tolerance dish named", [r["recipeId"] for r in flagged] == ["rid-b"])
        check("dry_run flag in report", report["dry_run"] is True)

        # -- quarantine run: archives + verifies
        b3 = os.path.join(root, "b3")
        batch_dir(b3, fps, ids)
        client3 = FakeClient({"rid-a": recipe(engine_nps()),
                              "rid-b": recipe(engine_nps(kcal=520))})
        report = CBN.run(b3, client3, True, False, tol_path)
        check("quarantine archives the over-tolerance dish", client3.archived == ["rid-b"])
        check("quarantine exit 1", report["exit_code"] == 1)
        check("ok dish not archived", "rid-a" not in client3.archived)

        # -- not-recomputed dish flagged
        b4 = os.path.join(root, "b4")
        batch_dir(b4, fps, ids)
        client4 = FakeClient({"rid-a": recipe(engine_nps()),
                              "rid-b": recipe(None)})  # nutritionPerServing null
        report = CBN.run(b4, client4, False, False, tol_path)
        rows = {r["recipeId"]: r for r in report["per_dish"]}
        check("not-recomputed flagged",
              rows["rid-b"]["status"] == CBN.STATUS_NOT_RECOMPUTED)
        check("not-recomputed drives exit 1", report["exit_code"] == 1)

        # -- currentVersion != 1 -> NOT_COMPARABLE, not quarantined
        b5 = os.path.join(root, "b5")
        batch_dir(b5, fps, ids)
        client5 = FakeClient({"rid-a": recipe(engine_nps()),
                              "rid-b": recipe(engine_nps(kcal=999), current_version=3)})
        report = CBN.run(b5, client5, True, False, tol_path)
        rows = {r["recipeId"]: r for r in report["per_dish"]}
        check("adapted dish NOT_COMPARABLE",
              rows["rid-b"]["status"] == CBN.STATUS_NOT_COMPARABLE)
        check("adapted dish not archived", client5.archived == [])

        # -- join mismatch = harness error
        b6 = os.path.join(root, "b6")
        batch_dir(b6, fps, ids)
        with open(os.path.join(b6, "ingest_report.json"), "w") as fh:
            json.dump({"batchId": "batch-t", "recipeIds": [
                {"fp": "a" * 64, "recipeId": "rid-a", "versionId": "v",
                 "nutritionStatus": "CALCULATED"}]}, fh)  # b-fp expected but not ingested
        try:
            CBN.run(b6, FakeClient({"rid-a": recipe(engine_nps())}), False, False, tol_path)
            check("join mismatch raises harness error", False)
        except CBN.HarnessError:
            check("join mismatch raises harness error", True)

        # -- report schema fields
        b7 = os.path.join(root, "b7")
        batch_dir(b7, fps, ids)
        client7 = FakeClient({"rid-a": recipe(engine_nps()),
                              "rid-b": recipe(engine_nps(kcal=400))})
        report = CBN.run(b7, client7, False, False, tol_path)
        on_disk = json.load(open(os.path.join(b7, "divergence_report.json")))
        for field in ("header", "batch_id", "tolerances_version", "per_dish",
                      "summary", "exit_code"):
            check("report field %s" % field, field in on_disk)
        check("landmine tripwire named in header", "landmine" in on_disk["header"])
        for row in on_disk["per_dish"]:
            for field in ("fp", "recipeId", "kcalDelta", "worstMicro",
                          "worstMicroDelta", "status"):
                check("per_dish field %s" % field, field in row)
            break
        check("summary counts", on_disk["summary"] == {"ok": 2, "quarantined": 0})
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    test_diff_logic()
    test_run_lifecycle()
    if FAILURES:
        print("\n%d FAILURES: %s" % (len(FAILURES), FAILURES))
        sys.exit(1)
    print("\nall G08 harness tests passed")
    sys.exit(0)
