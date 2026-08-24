package com.example.mealprep.discovery.domain.service.internal.graphimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Java enforcement of the G09 {@code review/approved.json} verdict contract (schema {@code
 * graph-review/1}) — a faithful port of the spike's reference implementation {@code
 * corpus_expansion/review_bundle.py#validate_verdicts}, which G06 must re-apply before trusting the
 * approved set.
 *
 * <p>Load-bearing rules: {@code manifest_sha256} must equal the sha256 of the on-disk (stamped)
 * {@code manifest.json} bytes — a mismatch means the verdict file belongs to a different or
 * regenerated batch and is a REPLAY, refused outright; every batch fingerprint must land in exactly
 * one of approved/rejected (full mode; deterministic-sample rules in sample mode); rejection
 * reasons must be non-empty; {@code reviewed_by}/{@code reviewed_at} must be set once any verdict
 * exists; unknown fingerprints invalidate. Only a {@code complete} verdict file may drive an
 * ingest, and only its {@code approved} list.
 */
public final class GraphReviewContract {

  public static final String SCHEMA = "graph-review/1";

  public enum Status {
    COMPLETE,
    INCOMPLETE,
    INVALID
  }

  public record Result(
      Status status,
      List<String> errors,
      List<String> notes,
      List<String> approved,
      List<String> rejected) {}

  private GraphReviewContract() {}

  public static Result validate(Path batchDir, ObjectMapper mapper) {
    List<String> errors = new ArrayList<>();
    List<String> notes = new ArrayList<>();

    JsonNode verdicts;
    try {
      verdicts = mapper.readTree(batchDir.resolve("review").resolve("approved.json").toFile());
    } catch (IOException e) {
      errors.add("approved.json unreadable/unparseable: " + e.getClass().getSimpleName());
      return invalid(errors, notes);
    }
    if (!verdicts.isObject() || !SCHEMA.equals(verdicts.path("schema").asText(null))) {
      errors.add("schema must be \"" + SCHEMA + "\"");
      return invalid(errors, notes);
    }

    String manifestSha;
    try {
      manifestSha = sha256Hex(Files.readAllBytes(batchDir.resolve("manifest.json")));
    } catch (IOException e) {
      errors.add("manifest.json unreadable — cannot verify binding");
      return invalid(errors, notes);
    }
    if (!manifestSha.equals(verdicts.path("manifest_sha256").asText(null))) {
      errors.add(
          "manifest_sha256 mismatch: verdict file does not bind to this batch's stamped"
              + " manifest (replay refused)");
    }

    TreeSet<String> batchFps;
    try (Stream<Path> files = Files.list(batchDir.resolve("recipes"))) {
      batchFps = new TreeSet<>();
      files
          .map(p -> p.getFileName().toString())
          .filter(name -> name.endsWith(".json"))
          .forEach(name -> batchFps.add(name.substring(0, name.length() - ".json".length())));
    } catch (IOException e) {
      errors.add("recipes/ unreadable — cannot enumerate batch fingerprints");
      return invalid(errors, notes);
    }

    List<String> approvedList = new ArrayList<>();
    JsonNode approvedNode = verdicts.path("approved");
    if (approvedNode.isArray() && allTextual(approvedNode)) {
      approvedNode.forEach(fp -> approvedList.add(fp.asText()));
    } else {
      errors.add("approved must be a list of fingerprint strings");
    }

    List<String> rejectedFps = new ArrayList<>();
    JsonNode rejectedNode = verdicts.path("rejected");
    if (rejectedNode.isArray()) {
      for (int i = 0; i < rejectedNode.size(); i++) {
        JsonNode entry = rejectedNode.get(i);
        if (!entry.isObject() || !entry.path("fp").isTextual()) {
          errors.add("rejected[" + i + "] must be {fp, reason}");
          continue;
        }
        String fp = entry.path("fp").asText();
        rejectedFps.add(fp);
        String reason = entry.path("reason").isTextual() ? entry.path("reason").asText() : null;
        if (reason == null || reason.isBlank()) {
          errors.add("rejected[" + i + "] (" + fp + ") has an empty reason");
        }
      }
    } else {
      errors.add("rejected must be a list of {fp, reason} objects");
    }

    TreeSet<String> approvedSet = new TreeSet<>(approvedList);
    TreeSet<String> rejectedSet = new TreeSet<>(rejectedFps);
    if (approvedList.size() != approvedSet.size()) {
      errors.add("duplicate fingerprints inside approved");
    }
    if (rejectedFps.size() != rejectedSet.size()) {
      errors.add("duplicate fingerprints inside rejected");
    }
    TreeSet<String> both = new TreeSet<>(approvedSet);
    both.retainAll(rejectedSet);
    if (!both.isEmpty()) {
      errors.add("fingerprints listed in BOTH approved and rejected: " + both);
    }
    TreeSet<String> unknown = new TreeSet<>(approvedSet);
    unknown.addAll(rejectedSet);
    unknown.removeAll(batchFps);
    if (!unknown.isEmpty()) {
      errors.add("unknown fingerprints (not in this batch): " + unknown);
    }
    Set<String> verdictFps = new LinkedHashSet<>(approvedSet);
    verdictFps.addAll(rejectedSet);
    if (!verdictFps.isEmpty()
        && (blank(verdicts.path("reviewed_by")) || blank(verdicts.path("reviewed_at")))) {
      errors.add("reviewed_by/reviewed_at must be set once any verdict exists");
    }

    TreeSet<String> missing = new TreeSet<>(batchFps);
    missing.removeAll(approvedSet);
    missing.removeAll(rejectedSet);

    String mode = verdicts.path("review_mode").asText("full");
    boolean complete = false;
    if ("full".equals(mode)) {
      complete = missing.isEmpty() && !verdictFps.isEmpty();
    } else {
      Double rate = sampleRate(mode);
      if (rate == null) {
        errors.add("review_mode \"" + mode + "\" is neither 'full' nor a valid 'sample:<rate>'");
      } else {
        List<String> expected = selectSample(batchFps, rate);
        List<String> declared = new ArrayList<>();
        JsonNode sampleNode = verdicts.path("sample");
        if (sampleNode.isArray()) {
          sampleNode.forEach(fp -> declared.add(fp.asText()));
        }
        List<String> sample = declared;
        if (!declared.equals(expected)) {
          errors.add(
              "sample list does not match the deterministic selection for rate "
                  + rate
                  + " ("
                  + expected.size()
                  + " expected)");
          sample = expected;
        }
        TreeSet<String> sampleMissing = new TreeSet<>(sample);
        sampleMissing.removeAll(approvedSet);
        sampleMissing.removeAll(rejectedSet);
        TreeSet<String> sampleRejected = new TreeSet<>(sample);
        sampleRejected.retainAll(rejectedSet);
        if (!sampleMissing.isEmpty()) {
          notes.add("sampled fingerprints still unreviewed: " + sampleMissing.size());
        } else if (!sampleRejected.isEmpty()) {
          notes.add(
              "sampled rejection(s) present: FULL review of the batch is required ("
                  + missing.size()
                  + " dish(es) still unreviewed); the consecutive-pass counter resets");
          complete = missing.isEmpty();
        } else {
          if (!missing.isEmpty()) {
            notes.add(
                "sampled subset fully approved: auto-list the "
                    + missing.size()
                    + " un-sampled fingerprints in approved to complete");
          }
          complete = missing.isEmpty();
        }
      }
    }

    Status status =
        !errors.isEmpty() ? Status.INVALID : complete ? Status.COMPLETE : Status.INCOMPLETE;
    return new Result(status, errors, notes, List.copyOf(approvedSet), List.copyOf(rejectedSet));
  }

  /** Deterministic sampling: sorted fingerprints, every k-th, k = round(1/rate). */
  static List<String> selectSample(TreeSet<String> fps, double rate) {
    int k = (int) Math.round(1.0 / rate);
    List<String> sorted = new ArrayList<>(fps);
    List<String> sample = new ArrayList<>();
    for (int i = 0; i < sorted.size(); i += k) {
      sample.add(sorted.get(i));
    }
    return sample;
  }

  static Double sampleRate(String mode) {
    if (mode == null || !mode.startsWith("sample:")) {
      return null;
    }
    double rate;
    try {
      rate = Double.parseDouble(mode.substring("sample:".length()));
    } catch (NumberFormatException e) {
      return null;
    }
    return (rate > 0 && rate < 1) ? rate : null;
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static boolean allTextual(JsonNode array) {
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private static boolean blank(JsonNode node) {
    return !node.isTextual() || node.asText().isBlank();
  }

  private static Result invalid(List<String> errors, List<String> notes) {
    return new Result(Status.INVALID, errors, notes, List.of(), List.of());
  }
}
