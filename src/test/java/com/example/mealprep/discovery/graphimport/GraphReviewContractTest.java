package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphReviewContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage of the graph-review/1 verdict-contract port (G09 rules enforced by G06). File
 * fixtures are built in a temp dir per case — the contract is file-shaped by design (the
 * manifest_sha256 binds to on-disk bytes).
 */
class GraphReviewContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FP_A = "a".repeat(64);
  private static final String FP_B = "b".repeat(64);
  private static final String FP_C = "c".repeat(64);

  @TempDir Path batchDir;

  private String manifestSha;

  @BeforeEach
  void writeBatchSkeleton() throws Exception {
    Files.createDirectories(batchDir.resolve("recipes"));
    Files.createDirectories(batchDir.resolve("review"));
    byte[] manifestBytes =
        "{\"schema\": \"graph-batch/1\", \"batch_id\": \"batch-20260721-1\"}\n".getBytes();
    Files.write(batchDir.resolve("manifest.json"), manifestBytes);
    manifestSha = sha256(manifestBytes);
    for (String fp : List.of(FP_A, FP_B, FP_C)) {
      Files.writeString(batchDir.resolve("recipes").resolve(fp + ".json"), "{}");
    }
  }

  private ObjectNode verdicts() {
    ObjectNode v = MAPPER.createObjectNode();
    v.put("schema", "graph-review/1");
    v.put("batch_id", "batch-20260721-1");
    v.put("manifest_sha256", manifestSha);
    v.put("review_mode", "full");
    v.put("reviewed_by", "irene");
    v.put("reviewed_at", "2026-07-21T10:00:00Z");
    v.putArray("approved");
    v.putArray("rejected");
    v.put("notes", "");
    return v;
  }

  private GraphReviewContract.Result validate(ObjectNode v) throws IOException {
    Files.writeString(
        batchDir.resolve("review").resolve("approved.json"), MAPPER.writeValueAsString(v));
    return GraphReviewContract.validate(batchDir, MAPPER);
  }

  @Test
  void completeFullReview() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_B);
    v.withArray("rejected").addObject().put("fp", FP_C).put("reason", "too salty");
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.COMPLETE);
    assertThat(result.errors()).isEmpty();
    assertThat(result.approved()).containsExactly(FP_A, FP_B);
    assertThat(result.rejected()).containsExactly(FP_C);
  }

  @Test
  void manifestShaMismatchIsAReplayRefusal() throws Exception {
    ObjectNode v = verdicts();
    v.put("manifest_sha256", "0".repeat(64));
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C);
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("replay refused"));
  }

  @Test
  void wrongSchemaInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.put("schema", "graph-review/2");
    assertThat(validate(v).status()).isEqualTo(GraphReviewContract.Status.INVALID);
  }

  @Test
  void missingVerdictIsIncompleteNotInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_B); // FP_C has no verdict
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INCOMPLETE);
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void emptySkeletonIsIncomplete() throws Exception {
    // the exporter-written skeleton (no verdicts at all) must never drive an ingest
    ObjectNode v = verdicts();
    v.putNull("reviewed_by");
    v.putNull("reviewed_at");
    assertThat(validate(v).status()).isEqualTo(GraphReviewContract.Status.INCOMPLETE);
  }

  @Test
  void fingerprintInBothListsInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C);
    v.withArray("rejected").addObject().put("fp", FP_A).put("reason", "dup verdict");
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("BOTH approved and rejected"));
  }

  @Test
  void unknownFingerprintInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C).add("d".repeat(64));
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("unknown fingerprints"));
  }

  @Test
  void emptyRejectionReasonInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_B);
    v.withArray("rejected").addObject().put("fp", FP_C).put("reason", "  ");
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("empty reason"));
  }

  @Test
  void verdictsWithoutReviewerIdentityInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.putNull("reviewed_by");
    v.putNull("reviewed_at");
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C);
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("reviewed_by"));
  }

  @Test
  void duplicateFingerprintsInsideApprovedInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.withArray("approved").add(FP_A).add(FP_A).add(FP_B).add(FP_C);
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("duplicate fingerprints inside approved"));
  }

  @Test
  void missingApprovedJsonInvalid() {
    GraphReviewContract.Result result = GraphReviewContract.validate(batchDir, MAPPER);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
  }

  // ===== sample mode (G09 escalation policy) =====

  @Test
  void sampleModeFullyApprovedSampleAutoListsRest() throws Exception {
    // rate 0.5 → k=2 → sample = [FP_A, FP_C] over the sorted 3 fps
    ObjectNode v = verdicts();
    v.put("review_mode", "sample:0.5");
    v.putArray("sample").add(FP_A).add(FP_C);
    v.withArray("approved").add(FP_A).add(FP_C);
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INCOMPLETE);
    assertThat(result.notes()).anyMatch(n -> n.contains("auto-list the 1 un-sampled"));
    // after auto-listing, complete:
    v.withArray("approved").add(FP_B);
    assertThat(validate(v).status()).isEqualTo(GraphReviewContract.Status.COMPLETE);
  }

  @Test
  void sampleModeDeclaredSampleMustMatchDeterministicSelection() throws Exception {
    ObjectNode v = verdicts();
    v.put("review_mode", "sample:0.5");
    v.putArray("sample").add(FP_B); // not the deterministic every-k-th selection
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C);
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INVALID);
    assertThat(result.errors()).anyMatch(e -> e.contains("deterministic selection"));
  }

  @Test
  void sampleModeSampledRejectionDemandsFullReview() throws Exception {
    ObjectNode v = verdicts();
    v.put("review_mode", "sample:0.5");
    v.putArray("sample").add(FP_A).add(FP_C);
    v.withArray("approved").add(FP_A);
    v.withArray("rejected").addObject().put("fp", FP_C).put("reason", "unbalanced");
    GraphReviewContract.Result result = validate(v);
    assertThat(result.status()).isEqualTo(GraphReviewContract.Status.INCOMPLETE);
    assertThat(result.notes()).anyMatch(n -> n.contains("FULL review"));
    // full review completed → complete
    v.withArray("approved").add(FP_B);
    assertThat(validate(v).status()).isEqualTo(GraphReviewContract.Status.COMPLETE);
  }

  @Test
  void invalidSampleRateInvalid() throws Exception {
    ObjectNode v = verdicts();
    v.put("review_mode", "sample:2.0");
    v.withArray("approved").add(FP_A).add(FP_B).add(FP_C);
    assertThat(validate(v).status()).isEqualTo(GraphReviewContract.Status.INVALID);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
