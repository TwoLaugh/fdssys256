package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.dto.SoftPreferenceBundleDto;
import com.example.mealprep.preference.api.mapper.HardConstraintsMapper;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.BatchCooking;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.FreezerTolerance;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealSchedule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTiming;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyMode;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyTolerance;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.PrepDay;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CuisinePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreferences;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.example.mealprep.preference.domain.entity.LifestyleConfig;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.repository.HardIntoleranceRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.testdata.LifestyleConfigTestData;
import com.example.mealprep.preference.testdata.TasteProfileTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit test for the NON-VECTOR soft-preference projection (household/preference-01g), exercised
 * through {@link PreferenceServiceImpl#getSoftPreferences(UUID)} and {@link
 * PreferenceServiceImpl#getSoftPreferencesByUserIds(List)} with the two soft-pref repositories
 * mocked at the module boundary. The two repositories are field-injected on the impl, so they are
 * set via {@link ReflectionTestUtils} (mirroring how the impl is wired by Spring in production).
 *
 * <p>The headline guarantee asserted here is the <b>scope boundary</b>: a user whose taste vector
 * is {@code EMBEDDED} (built) and a user whose vector is {@code PENDING} (unbuilt) — identical
 * document, differing ONLY in the {@code tasteVector*} entity status fields — produce
 * byte-identical bundles. That proves the projection never reads the vector and the merge never
 * waits on embeddings.
 */
@ExtendWith(MockitoExtension.class)
class SoftPreferenceProjectionTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private HardConstraintsAuditLogRepository auditLogRepository;
  @Mock private HardIntoleranceRepository hardIntoleranceRepository;
  @Mock private TasteProfileRepository tasteProfileRepository;
  @Mock private LifestyleConfigRepository lifestyleConfigRepository;

  private final HardConstraintsMapper mapper =
      new com.example.mealprep.preference.api.mapper.HardConstraintsMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

  private PreferenceServiceImpl service() {
    PreferenceServiceImpl impl =
        new PreferenceServiceImpl(
            hardConstraintsRepository,
            auditLogRepository,
            hardIntoleranceRepository,
            mapper,
            null,
            objectMapper,
            fixedClock);
    ReflectionTestUtils.setField(impl, "tasteProfileRepository", tasteProfileRepository);
    ReflectionTestUtils.setField(impl, "lifestyleConfigRepository", lifestyleConfigRepository);
    return impl;
  }

  private TasteProfile tasteProfileEntity(
      UUID userId, TasteProfileDocument document, TasteVectorStatus status) {
    return TasteProfile.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .document(document)
        .documentVersion(document.version())
        .basedOnFeedbackCount(0)
        .tasteVectorStatus(status)
        .optimisticVersion(0L)
        .createdAt(Instant.parse("2026-05-20T10:00:00Z"))
        .updatedAt(Instant.parse("2026-05-20T10:00:00Z"))
        .build();
  }

  private LifestyleConfig lifestyleConfigEntity(UUID userId, LifestyleConfigDocument document) {
    return LifestyleConfig.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .document(document)
        .optimisticVersion(0L)
        .createdAt(Instant.parse("2026-05-20T10:00:00Z"))
        .updatedAt(Instant.parse("2026-05-20T10:00:00Z"))
        .build();
  }

  // ---------------- vector exclusion (the headline scope-boundary guarantee) ----------------

  /**
   * The projection MUST NOT read the taste vector: a user with a built ({@code EMBEDDED}) vector
   * and a user with an unbuilt ({@code PENDING}) vector — same document — produce identical
   * bundles. If the projection ever read the vector status, these would diverge.
   */
  @Test
  void getSoftPreferences_builtVectorVsPendingVector_produceIdenticalBundles() {
    UUID built = UUID.randomUUID();
    UUID pending = UUID.randomUUID();
    TasteProfileDocument document = TasteProfileTestData.populatedDocument(1);

    TasteProfile builtProfile = tasteProfileEntity(built, document, TasteVectorStatus.EMBEDDED);
    builtProfile.setTasteVectorDocVersion(1);
    builtProfile.setTasteVectorModelId("text-embedding-3-small");
    builtProfile.setTasteVectorEmbeddedAt(Instant.parse("2026-05-21T10:00:00Z"));
    TasteProfile pendingProfile = tasteProfileEntity(pending, document, TasteVectorStatus.PENDING);

    when(tasteProfileRepository.findByUserId(built)).thenReturn(Optional.of(builtProfile));
    when(tasteProfileRepository.findByUserId(pending)).thenReturn(Optional.of(pendingProfile));
    when(lifestyleConfigRepository.findByUserId(built)).thenReturn(Optional.empty());
    when(lifestyleConfigRepository.findByUserId(pending)).thenReturn(Optional.empty());

    SoftPreferenceBundleDto builtBundle = service().getSoftPreferences(built).orElseThrow();
    SoftPreferenceBundleDto pendingBundle = service().getSoftPreferences(pending).orElseThrow();

    // Identical projected taste profile (ingredientLikes / cuisineLikes / avoidList) regardless of
    // vector status; only the userId differs.
    assertThat(builtBundle.tasteProfile()).isEqualTo(pendingBundle.tasteProfile());
    assertThat(pendingProfile.getTasteVectorStatus()).isEqualTo(TasteVectorStatus.PENDING);
    // A PENDING/unbuilt user still gets a fully-populated like-map bundle.
    assertThat(pendingBundle.tasteProfile().ingredientLikes()).isNotEmpty();
  }

  // ---------------- like-map projection ----------------

  @Test
  void getSoftPreferences_ingredientFavouritesAndDisliked_mapToPlusOneAndMinusOne_anAvoidEntry() {
    UUID userId = UUID.randomUUID();
    LocalDate today = LocalDate.parse("2026-05-20");
    TasteProfileDocument document =
        withIngredients(
            TasteProfileTestData.populatedDocument(1),
            new IngredientPreferences(
                List.of(
                    new IngredientPreference(
                        "salmon", 12, today, IngredientPreferenceSource.FEEDBACK)),
                List.of(
                    new IngredientPreference(
                        "kale", 4, today, IngredientPreferenceSource.INFERRED)),
                List.of(),
                List.of()));
    when(tasteProfileRepository.findByUserId(userId))
        .thenReturn(Optional.of(tasteProfileEntity(userId, document, TasteVectorStatus.PENDING)));
    when(lifestyleConfigRepository.findByUserId(userId)).thenReturn(Optional.empty());

    SoftPreferenceBundleDto bundle = service().getSoftPreferences(userId).orElseThrow();

    assertThat(bundle.tasteProfile().ingredientLikes())
        .containsEntry("salmon", BigDecimal.ONE)
        .containsEntry("kale", BigDecimal.ONE.negate());
    // The disliked ingredient is also surfaced as an avoid-list key (NOT an allergen).
    assertThat(bundle.tasteProfile().avoidList()).containsExactly("kale");
  }

  @Test
  void getSoftPreferences_cuisineFavouritesEnjoysLessPreferred_mapToScaledLikeScores() {
    UUID userId = UUID.randomUUID();
    TasteProfileDocument document =
        withCuisines(
            TasteProfileTestData.populatedDocument(1),
            new CuisinePreferences(
                List.of("japanese"), List.of("italian"), List.of("indian"), null));
    when(tasteProfileRepository.findByUserId(userId))
        .thenReturn(Optional.of(tasteProfileEntity(userId, document, TasteVectorStatus.EMBEDDED)));
    when(lifestyleConfigRepository.findByUserId(userId)).thenReturn(Optional.empty());

    SoftPreferenceBundleDto bundle = service().getSoftPreferences(userId).orElseThrow();

    assertThat(bundle.tasteProfile().cuisineLikes())
        .containsEntry("japanese", BigDecimal.ONE)
        .containsEntry("italian", new BigDecimal("0.5"))
        .containsEntry("indian", new BigDecimal("-0.5"));
  }

  // ---------------- lifestyle projection ----------------

  @Test
  void getSoftPreferences_lifestyleWindowNoveltyBatch_projectedFromRichDocument() {
    UUID userId = UUID.randomUUID();
    LifestyleConfigDocument document = lifestyleWithWindowNoveltyBatch();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(lifestyleConfigRepository.findByUserId(userId))
        .thenReturn(Optional.of(lifestyleConfigEntity(userId, document)));

    SoftPreferenceBundleDto bundle = service().getSoftPreferences(userId).orElseThrow();

    // Earliest start (07:00 breakfast) + latest end (20:00 dinner) across the per-slot ranges.
    assertThat(bundle.lifestyleConfig().mealTimingWindowStart()).isEqualTo("07:00");
    assertThat(bundle.lifestyleConfig().mealTimingWindowEnd()).isEqualTo("20:00");
    // Max newPerWeek across slots, clamped to [0,100].
    assertThat(bundle.lifestyleConfig().noveltyTolerancePercent()).isEqualTo(40);
    // At least one prep day → batch preferred.
    assertThat(bundle.lifestyleConfig().batchCookingPreferred()).isTrue();
    // No taste profile → null-fielded.
    assertThat(bundle.tasteProfile()).isNull();
  }

  @Test
  void getSoftPreferences_fullLifestyleDocument_noNewPerWeek_noveltyPercentNull() {
    UUID userId = UUID.randomUUID();
    // LifestyleConfigTestData.fullDocument() carries a rotation NoveltyMode with newPerWeek ==
    // null.
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(lifestyleConfigRepository.findByUserId(userId))
        .thenReturn(
            Optional.of(lifestyleConfigEntity(userId, LifestyleConfigTestData.fullDocument())));

    SoftPreferenceBundleDto bundle = service().getSoftPreferences(userId).orElseThrow();

    assertThat(bundle.lifestyleConfig().noveltyTolerancePercent()).isNull();
    assertThat(bundle.lifestyleConfig().mealTimingWindowStart()).isEqualTo("07:00");
    assertThat(bundle.lifestyleConfig().mealTimingWindowEnd()).isEqualTo("20:00");
    assertThat(bundle.lifestyleConfig().batchCookingPreferred()).isTrue();
  }

  // ---------------- null-field bundles + presence ----------------

  @Test
  void getSoftPreferences_userWithNeitherProfileNorConfig_returnsEmptyOptional() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(lifestyleConfigRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getSoftPreferences(userId)).isEmpty();
  }

  @Test
  void getSoftPreferences_userWithOnlyTasteProfile_returnsBundleWithNullLifestyle() {
    UUID userId = UUID.randomUUID();
    when(tasteProfileRepository.findByUserId(userId))
        .thenReturn(
            Optional.of(
                tasteProfileEntity(
                    userId, TasteProfileTestData.populatedDocument(1), TasteVectorStatus.PENDING)));
    when(lifestyleConfigRepository.findByUserId(userId)).thenReturn(Optional.empty());

    SoftPreferenceBundleDto bundle = service().getSoftPreferences(userId).orElseThrow();

    assertThat(bundle.tasteProfile()).isNotNull();
    assertThat(bundle.lifestyleConfig()).isNull();
  }

  // ---------------- batch: order + count invariant ----------------

  @Test
  void getSoftPreferencesByUserIds_returnsOneBundlePerUserInInputOrder_nullFieldedForMissing() {
    UUID withProfile = UUID.randomUUID();
    UUID withConfig = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    List<UUID> input = List.of(withProfile, missing, withConfig);

    when(tasteProfileRepository.findByUserIdIn(input))
        .thenReturn(
            List.of(
                tasteProfileEntity(
                    withProfile,
                    TasteProfileTestData.populatedDocument(1),
                    TasteVectorStatus.PENDING)));
    when(lifestyleConfigRepository.findByUserIdIn(input))
        .thenReturn(
            List.of(lifestyleConfigEntity(withConfig, LifestyleConfigTestData.batchCookingOnly())));

    List<SoftPreferenceBundleDto> bundles = service().getSoftPreferencesByUserIds(input);

    // Count + order invariant (the SPI contract: callers index by position).
    assertThat(bundles).hasSize(3);
    assertThat(bundles.get(0).userId()).isEqualTo(withProfile);
    assertThat(bundles.get(1).userId()).isEqualTo(missing);
    assertThat(bundles.get(2).userId()).isEqualTo(withConfig);

    assertThat(bundles.get(0).tasteProfile()).isNotNull();
    assertThat(bundles.get(0).lifestyleConfig()).isNull();
    // The missing user is present (not omitted) with a fully null-fielded bundle.
    assertThat(bundles.get(1).tasteProfile()).isNull();
    assertThat(bundles.get(1).lifestyleConfig()).isNull();
    assertThat(bundles.get(2).tasteProfile()).isNull();
    assertThat(bundles.get(2).lifestyleConfig().batchCookingPreferred()).isTrue();
  }

  @Test
  void getSoftPreferencesByUserIds_emptyOrNullInput_returnsEmptyList() {
    assertThat(service().getSoftPreferencesByUserIds(List.of())).isEmpty();
    assertThat(service().getSoftPreferencesByUserIds(null)).isEmpty();
  }

  // ---------------- SPI adapter delegation ----------------

  /**
   * The real {@link com.example.mealprep.preference.spi.internal.PreferenceSoftPreferencesReader}
   * SPI {@code @Component} is a thin adapter — it must return the preference query service's
   * bundles verbatim (same list, in order), not a fresh/empty list.
   */
  @Test
  void preferenceSoftPreferencesReader_delegatesToQueryService_returningItsBundlesVerbatim() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    List<UUID> input = List.of(a, b);
    List<SoftPreferenceBundleDto> expected =
        List.of(
            new SoftPreferenceBundleDto(a, null, null), new SoftPreferenceBundleDto(b, null, null));
    var queryService =
        org.mockito.Mockito.mock(
            com.example.mealprep.preference.domain.service.PreferenceQueryService.class);
    when(queryService.getSoftPreferencesByUserIds(input)).thenReturn(expected);

    var reader =
        new com.example.mealprep.preference.spi.internal.PreferenceSoftPreferencesReader(
            queryService);

    assertThat(reader.getSoftPreferencesByUserIds(input)).isSameAs(expected);
  }

  // ---------------- helpers ----------------

  private static TasteProfileDocument withIngredients(
      TasteProfileDocument base, IngredientPreferences ingredientPreferences) {
    return new TasteProfileDocument(
        base.lastUpdated(),
        base.version(),
        base.basedOnFeedbackCount(),
        base.feedbackCursor(),
        base.softConstraints(),
        base.flavourPreferences(),
        base.texturePreferences(),
        ingredientPreferences,
        base.cuisinePreferences(),
        base.cookingPreferences(),
        base.portionStyle(),
        base.householdContext(),
        base.recipesToRepeat(),
        base.recipesToAvoid(),
        base.activeExperiments(),
        base.learnedInsights());
  }

  private static TasteProfileDocument withCuisines(
      TasteProfileDocument base, CuisinePreferences cuisinePreferences) {
    return new TasteProfileDocument(
        base.lastUpdated(),
        base.version(),
        base.basedOnFeedbackCount(),
        base.feedbackCursor(),
        base.softConstraints(),
        base.flavourPreferences(),
        base.texturePreferences(),
        base.ingredientPreferences(),
        cuisinePreferences,
        base.cookingPreferences(),
        base.portionStyle(),
        base.householdContext(),
        base.recipesToRepeat(),
        base.recipesToAvoid(),
        base.activeExperiments(),
        base.learnedInsights());
  }

  /**
   * A lifestyle document carrying a multi-slot meal-timing window, multiple novelty slots with
   * differing {@code newPerWeek}, and a batch prep day — drives the window/novelty/batch
   * projection.
   */
  private static LifestyleConfigDocument lifestyleWithWindowNoveltyBatch() {
    return new LifestyleConfigDocument(
        null,
        new MealTiming(
            new MealSchedule(
                Map.of(
                    "breakfast", "07:00-08:00", "lunch", "12:00-13:00", "dinner", "19:00-20:00")),
            "tight",
            null),
        new NoveltyTolerance(
            Map.of(
                "dinner", new NoveltyMode("high_variety", null, null, null, 40),
                "lunch", new NoveltyMode("high_variety", null, null, null, 25)),
            Map.of(),
            Map.of()),
        null,
        new BatchCooking(
            List.of(new PrepDay("SUNDAY", "10:00-12:00", 2, 4)),
            Map.of(),
            "fridge-first",
            new FreezerTolerance(true, 2, List.of()),
            false,
            "moderate"),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
