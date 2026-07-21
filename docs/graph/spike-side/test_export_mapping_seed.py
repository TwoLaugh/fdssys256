# -*- coding: utf-8 -*-
"""Tests for export_mapping_seed.py (G05 spike side).

DESTINED FOR `culinary-graph-spike/corpus_expansion/`; run from anywhere with
SPIKE_ROOT env or --spike-root style default (parent dir when placed in
corpus_expansion/).

Covers: derived counts (never hardcoded blindly -- the 1,179/1,113/66 pins are
contract copies expected to break loudly on canon growth), normalise-identity,
duplicate-collapse invariant (fail-loudly path), conflicting-group WARN,
G04-key-only micros, no-vitamins rule, _meta stamping, determinism.

Run: python test_export_mapping_seed.py [spike_root]
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPIKE_ROOT = os.path.abspath(
    sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
        "SPIKE_ROOT", os.path.join(HERE, "..")))

sys.path.insert(0, HERE)
import export_mapping_seed as EMS  # noqa: E402

EMS._bootstrap(SPIKE_ROOT)
import corpus_io  # noqa: E402
import nutrition_keys as NK  # noqa: E402
from canonicalize import CANON_DEDUPE  # noqa: E402

with open(os.path.join(SPIKE_ROOT, "graph_spike", "corpus_nutrition.json"),
          encoding="utf-8") as f:
    NUTRITION = json.load(f)
NK_TABLE = NK.load_table(os.path.join(SPIKE_ROOT, "export", "nutrition_keys.json"))


def _rows(warn=lambda *a: None, nutrition=None, dedupe=None):
    return EMS.build_rows(nutrition or NUTRITION, dedupe or CANON_DEDUPE, NK,
                          NK_TABLE, "cafebabecafebabe", warn=warn)


def test_counts_derived_and_pinned():
    rows, counts = _rows()
    canon = [k for k in NUTRITION if k not in CANON_DEDUPE]
    assert counts["total"] == len(rows) == len(canon)
    assert counts["usda"] == sum(1 for r in rows if r["source"] == "USDA")
    assert counts["manual"] == sum(1 for r in rows if r["source"] == "MANUAL")
    assert counts["usda"] + counts["manual"] == counts["total"]
    # contract-copy pins (measured 2026-07-20 under the canonical-name-wins rule;
    # EXPECTED to break loudly on canon growth -- re-measure, don't fudge):
    assert counts == {"total": 1179, "usda": 1113, "manual": 66}, counts


def test_row_shape_and_source_rule():
    rows, _ = _rows()
    by_term = {r["searchTerm"]: r for r in rows}
    assert [r["searchTerm"] for r in rows] == sorted(by_term)  # deterministic order
    for r in rows:
        assert EMS.normalise_key(r["searchTerm"]) == r["searchTerm"]
        assert r["source"] in ("USDA", "MANUAL")
        assert (r["externalId"] is not None) == (r["source"] == "USDA")
        assert r["basisNote"].startswith("consumed-basis; spike canon corpus@")
        assert len(r["basisNote"]) <= 255
        doc = r["nutritionPer100g"]
        assert set(doc) == {"calories", "proteinG", "carbsG", "fatG", "fibreG",
                            "saturatedFatG", "micros"}
        assert "vitamins" not in doc
    # winning-row rule on the review's mixed-fdc groups: canonical row decides.
    assert by_term["salt"]["source"] == "MANUAL"
    assert by_term["sesame seed"]["source"] == "MANUAL"
    assert by_term["cayenne"]["source"] == "USDA"
    assert by_term["coriander seed"]["source"] == "USDA"


def test_micros_g04_keys_only_and_bridge():
    rows, _ = _rows()
    mapped = {v["engine"] for v in NK_TABLE["map"].values()
              if v["target"] in ("micros", "typed+micros")}
    engine_only = {k for k in NK_TABLE["engine_only_no_spike_source"]
                   if not k.startswith("_")}
    for r in rows:
        micros = r["nutritionPer100g"]["micros"]
        assert set(micros) <= mapped, r["searchTerm"]
        assert not set(micros) & engine_only, "engine-only key fabricated"
        entry = NUTRITION[r["searchTerm"]]
        if entry.get("sat") is not None:
            assert "saturated_fat_g" in micros  # the bridge key
            assert micros["saturated_fat_g"] == r["nutritionPer100g"]["saturatedFatG"]


def test_values_pass_through_canon_as_is():
    rows, _ = _rows()
    r = next(x for x in rows if x["searchTerm"] == "rice")
    entry = NUTRITION["rice"]
    assert r["nutritionPer100g"]["calories"] == entry["kcal"]
    assert r["nutritionPer100g"]["proteinG"] == entry["p"]
    assert r["nutritionPer100g"]["micros"]["iron_mg"] == entry["fe"]


def test_duplicate_collapse_invariant_fails_loudly():
    # doctor: a raw variant whose canonical row is missing from the table
    nut = dict(NUTRITION)
    dedupe = dict(CANON_DEDUPE)
    nut["carrots x"] = dict(NUTRITION["carrot"])
    dedupe["carrots x"] = "no such canonical row"
    try:
        _rows(nutrition=nut, dedupe=dedupe)
    except EMS.SeedExportError as e:
        assert "collapse invariant" in str(e)
    else:
        raise AssertionError("expected SeedExportError")


def test_conflicting_group_warns_with_losers():
    warnings = []
    _rows(warn=warnings.append)
    assert warnings, "expected conflict WARNs (23 conflicting groups measured)"
    assert all("discards conflicting raw row" in w for w in warnings)


def test_normalise_violation_fails_loudly():
    nut = dict(NUTRITION)
    nut["Bad  Name "] = dict(NUTRITION["rice"])
    try:
        _rows(nutrition=nut)
    except EMS.SeedExportError as e:
        assert "normal-form" in str(e)
    else:
        raise AssertionError("expected SeedExportError")


def test_meta_stamp_and_determinism():
    a1, p1 = EMS.export(SPIKE_ROOT, out_path=os.path.join(
        HERE, "_seed_tmp1.json"))
    a2, p2 = EMS.export(SPIKE_ROOT, out_path=os.path.join(
        HERE, "_seed_tmp2.json"))
    try:
        meta = a1["_meta"]
        assert meta["artifact"] == "graph-mapping-seed/1"
        assert meta["corpus_fingerprint"] == corpus_io.default_fingerprint()
        assert meta["nutrition_keys_version"] == NK_TABLE["_meta"]["version"]
        assert len(meta["spike_commit"]) == 7
        assert meta["counts"]["total"] == len(a1["rows"])
        with open(p1, "rb") as f1, open(p2, "rb") as f2:
            assert f1.read() == f2.read(), "artifact must be byte-deterministic"
    finally:
        for p in (p1, p2):
            if os.path.exists(p):
                os.remove(p)


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    for t in TESTS:
        t()
        print("PASS %s" % t.__name__)
    print("test_export_mapping_seed: %d/%d passed" % (len(TESTS), len(TESTS)))
