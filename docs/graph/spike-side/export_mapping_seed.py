# -*- coding: utf-8 -*-
"""G05 (spike side) -- seed-artifact generator: spike canon -> IngredientMapping seed rows.

DESTINED FOR the spike repo (`culinary-graph-spike/corpus_expansion/export_mapping_seed.py`).
It is delivered here (engine repo, docs/graph/spike-side/) because the G05/G06 implementation
session had the spike checkout as READ-ONLY input; run it with `--spike-root` pointing at the
spike checkout until it is committed there, at which point the default relative paths apply
unchanged. See docs/graph/README.md for the provenance of the committed artifact.

Reads `graph_spike/corpus_nutrition.json` through `corpus_io.canonical_name` (i.e. the
CANON_DEDUPE collapse) + G04's `nutrition_keys.to_engine_document`, and writes
`export/ingredient_mapping_seed.json`: one row per CANONICAL ingredient, engine-document
shaped (typed macros + canonical-key `micros`, incl. the `saturated_fat_g` bridge; never a
`vitamins` map).

Rules (ticket tickets/engine-integration/G05-ingredientmapping-seed.md):
  * one row per canonical name; duplicate-collapse rule: the raw entry whose key EQUALS the
    canonical name wins. The invariant "every multi-raw group contains its canonical-name raw
    row" is asserted and the export fails loudly if canon growth ever breaks it;
  * conflicting collapsed groups are WARNED (losing raw keys listed) -- visibility, not failure;
  * `source` = USDA + externalId = `_fdc` when the WINNING row carries one, else MANUAL/null
    (mixed-fdc groups resolve by the winning row -- review edit 2026-07-20);
  * values come from the QA'd canon AS-IS via G04's frozen table (no fresh USDA fetch);
  * every searchTerm must satisfy the engine's normalise(name) == name (replica below);
  * `_meta` is stamped with spike commit, corpus fingerprint and nutrition_keys version;
    the artifact is byte-deterministic for a given spike state (no wall-clock field).

Run: python export_mapping_seed.py [--spike-root <path>] [--out <path>] [--allow-dirty]
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

ARTIFACT_SCHEMA = "graph-mapping-seed/1"
BASIS = ("consumed-basis per-100g; grams exported by G01 are on the SAME basis "
         "(see nutrition_keys.json _meta.basis)")


class SeedExportError(Exception):
    """Loud failure: invariant violated, artifact must not be written."""


def normalise_key(name):
    """Python replica of the engine's IngredientMappingKeys.normalise
    (core/ingredient/IngredientMappingKeys.java): trim -> lowercase -> collapse
    whitespace. Idempotent."""
    return re.sub(r"\s+", " ", str(name).strip()).lower()


def _bootstrap(spike_root):
    root = os.path.abspath(spike_root or os.path.join(HERE, ".."))
    for sub in ("corpus_expansion", "graph_spike"):
        p = os.path.join(root, sub)
        if os.path.isdir(p) and p not in sys.path:
            sys.path.insert(0, p)
    return root


def spike_commit(root):
    try:
        return subprocess.run(["git", "rev-parse", "--short=7", "HEAD"], cwd=root,
                              capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as e:
        raise SeedExportError("cannot determine spike commit: %s" % e)


def _payload(entry):
    """The comparable (non-provenance) part of a raw nutrition entry."""
    return {k: v for k, v in entry.items() if not k.startswith("_")}


def build_rows(nutrition, canon_dedupe, nk, nk_table, corpus_sha, warn=print):
    """-> (rows sorted by searchTerm, counts dict). Pure; injectable for tests."""
    canon_keys = sorted(k for k in nutrition if k not in canon_dedupe)

    # Duplicate-collapse invariant: every raw variant's canonical row must itself exist
    # as a raw key (the winning row). Fail loudly on canon growth breaking it.
    orphans = sorted({canon_dedupe[r] for r in nutrition
                      if r in canon_dedupe and canon_dedupe[r] not in nutrition})
    if orphans:
        raise SeedExportError(
            "duplicate-collapse invariant violated: canonical name(s) %s have raw "
            "variants but no canonical-name row to win the collapse -- refusing to "
            "export (G05 collapse rule)" % orphans)

    # Conflicting-group visibility (WARN, not failure).
    losers_by_canon = {}
    for raw, canon in canon_dedupe.items():
        if raw in nutrition:
            losers_by_canon.setdefault(canon, []).append(raw)
    for canon, losers in sorted(losers_by_canon.items()):
        win = _payload(nutrition[canon])
        conflicting = [r for r in sorted(losers) if _payload(nutrition[r]) != win]
        if conflicting:
            warn("WARN collapse of %r discards conflicting raw row(s): %s"
                 % (canon, conflicting))

    mapped_engine_micros = {v["engine"] for v in nk_table["map"].values()
                            if v["target"] in ("micros", "typed+micros")}
    typed_fields = {"calories", "proteinG", "carbsG", "fatG", "fibreG",
                    "saturatedFatG"}

    rows = []
    n_usda = 0
    for name in canon_keys:
        if normalise_key(name) != name:
            raise SeedExportError(
                "searchTerm %r is not engine normal-form (normalise(name) != name)"
                % name)
        entry = nutrition[name]
        doc = nk.to_engine_document(entry, nk_table)
        if "vitamins" in doc:
            raise SeedExportError("translator emitted a 'vitamins' map for %r" % name)
        unknown_typed = set(doc) - typed_fields - {"micros"}
        if unknown_typed:
            raise SeedExportError("unexpected typed field(s) %s for %r"
                                  % (sorted(unknown_typed), name))
        bad_micros = set(doc["micros"]) - mapped_engine_micros
        if bad_micros:
            raise SeedExportError("non-G04 micro key(s) %s for %r"
                                  % (sorted(bad_micros), name))
        fdc = entry.get("_fdc")
        if fdc:
            n_usda += 1
            source, external_id = "USDA", str(fdc)
            desc = str(entry.get("_desc") or "").strip()
            note = "consumed-basis; spike canon corpus@%s; USDA %s%s" % (
                corpus_sha, fdc, (": " + desc) if desc else "")
        else:
            source, external_id = "MANUAL", None
            note = ("consumed-basis; spike canon corpus@%s; manual curation"
                    % corpus_sha)
        rows.append({
            "searchTerm": name,
            "source": source,
            "externalId": external_id,
            "basisNote": note[:255],
            "nutritionPer100g": {
                "calories": doc.get("calories"),
                "proteinG": doc.get("proteinG"),
                "carbsG": doc.get("carbsG"),
                "fatG": doc.get("fatG"),
                "fibreG": doc.get("fibreG"),
                "saturatedFatG": doc.get("saturatedFatG"),
                "micros": dict(sorted(doc["micros"].items())),
            },
        })
    counts = {"total": len(rows), "usda": n_usda, "manual": len(rows) - n_usda}
    return rows, counts


def export(spike_root=None, out_path=None, allow_dirty=False, warn=print):
    root = _bootstrap(spike_root)
    import corpus_io  # noqa: E402  (spike module)
    import nutrition_keys as nk  # noqa: E402  (spike module, G04)
    from canonicalize import CANON_DEDUPE  # noqa: E402  (spike module)

    with open(os.path.join(root, "graph_spike", "corpus_nutrition.json"),
              encoding="utf-8") as f:
        nutrition = json.load(f)
    nk_table = nk.load_table(os.path.join(root, "export", "nutrition_keys.json"))
    fp = corpus_io.default_fingerprint()
    commit = spike_commit(root)

    rows, counts = build_rows(nutrition, CANON_DEDUPE, nk, nk_table, fp["sha"],
                              warn=warn)
    artifact = {
        "_meta": {
            "artifact": ARTIFACT_SCHEMA,
            "spike_commit": commit,
            "corpus_fingerprint": fp,
            "nutrition_keys_version": nk_table["_meta"]["version"],
            "basis": BASIS,
            "counts": counts,
        },
        "rows": rows,
    }
    out_path = out_path or os.path.join(root, "export",
                                        "ingredient_mapping_seed.json")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(artifact, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    return artifact, out_path


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--spike-root", default=None,
                    help="spike checkout root (default: parent of this file)")
    ap.add_argument("--out", default=None,
                    help="output path (default: <root>/export/"
                         "ingredient_mapping_seed.json)")
    args = ap.parse_args(argv)
    try:
        artifact, out_path = export(args.spike_root, args.out)
    except SeedExportError as e:
        print("EXPORT FAILED: %s" % e, file=sys.stderr)
        return 2
    c = artifact["_meta"]["counts"]
    print("wrote %s: %d rows (%d USDA / %d MANUAL), spike %s, corpus %s"
          % (out_path, c["total"], c["usda"], c["manual"],
             artifact["_meta"]["spike_commit"],
             artifact["_meta"]["corpus_fingerprint"]["sha"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
