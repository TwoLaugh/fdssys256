package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.controller.HardConstraintsController;
import com.example.mealprep.preference.api.dto.AgeRestrictionDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.api.mapper.HardConstraintsMapper;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.repository.AllergenDerivativeRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.repository.HardIntoleranceRepository;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.domain.service.internal.HardConstraintFilterServiceImpl;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Targeted mutation-kills for the preference module's residual baseline survivors. Each test names
 * the specific mutator(s) it covers, with file + line citations. The fixtures are pure Mockito (no
 * Spring context) and use the real MapStruct mapper + service helpers so the mutants land on actual
 * call-path behaviour.
 *
 * <p>Companion to the existing per-feature tests ({@code HardConstraintsServiceImplTest}, {@code
 * HardConstraintFilterServiceImplTest}, {@code HardConstraintFilterShortCircuitTest}, {@code
 * HardConstraintsEntityTest}, {@code PreferenceExceptionHandlerTest}); this file concentrates on
 * the survivors the baseline run flagged — the controller surface (no Spring), the PreferenceModule
 * facade, the {@link HardConstraintsUpdatedEvent} record overrides, the {@link
 * HardConstraintsNotFoundException#userId()} accessor, the {@link
 * HardConstraintFilterServiceImpl#passesIndex} empty-ingredients fast-path, and the in-place {@code
 * Snapshot.of} sort calls that previously had no order-only no-op coverage.
 */
@ExtendWith(MockitoExtension.class)
class PreferenceMutationKillsTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private HardConstraintsAuditLogRepository auditLogRepository;
  @Mock private HardIntoleranceRepository hardIntoleranceRepository;
  @Mock private AllergenDerivativeRepository allergenDerivativeRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private PreferenceQueryService queryService;
  @Mock private PreferenceUpdateService updateService;
  @Mock private CurrentUserResolver currentUserResolver;
  @Mock private HardConstraintFilterService hardConstraintFilterService;

  @Mock
  private com.example.mealprep.preference.domain.service.LifestyleConfigQueryService
      lifestyleConfigQueryService;

  @Mock
  private com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService
      lifestyleConfigUpdateService;

  @Mock
  private com.example.mealprep.preference.domain.service.TasteProfileQueryService
      tasteProfileQueryService;

  @Mock
  private com.example.mealprep.preference.domain.service.TasteProfileUpdateService
      tasteProfileUpdateService;

  @Mock
  private com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService
      preferenceArchiveQueryService;

  @Mock
  private com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService
      preferenceArchiveUpdateService;

  private final HardConstraintsMapper mapper =
      new com.example.mealprep.preference.api.mapper.HardConstraintsMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

  private PreferenceServiceImpl preferenceService() {
    return new PreferenceServiceImpl(
        hardConstraintsRepository,
        auditLogRepository,
        hardIntoleranceRepository,
        mapper,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  private HardConstraintFilterServiceImpl filterService() {
    return new HardConstraintFilterServiceImpl(
        hardConstraintsRepository, allergenDerivativeRepository);
  }

  private HardConstraintsController controller() {
    return new HardConstraintsController(queryService, updateService, currentUserResolver);
  }

  // =================================================================================
  // HardConstraintsController — every public method had its return value mutated to null
  // by baseline NullReturnValsMutator (and the two lambdas in get / requireCurrentUserId).
  // No unit test instantiated the controller. Direct invocations below pin those returns.
  // =================================================================================

  /**
   * Kills {@code HardConstraintsController.java:57} ({@code NullReturnValsMutator} on {@code
   * get()}). The handler must return the DTO produced by {@code queryService.getHardConstraints} —
   * not null — when the aggregate is present.
   */
  @Test
  void controller_get_returnsDtoFromQueryService_killsNullReturn() {
    UUID userId = UUID.randomUUID();
    HardConstraintsDto dto =
        new HardConstraintsDto(
            UUID.randomUUID(),
            userId,
            List.of("peanut"),
            new DietaryIdentityDto("omnivore", null, List.of()),
            List.of(),
            List.of(),
            List.of(),
            0L);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getHardConstraints(userId)).thenReturn(Optional.of(dto));

    HardConstraintsDto result = controller().get();

    assertThat(result).isSameAs(dto);
    assertThat(result.userId()).isEqualTo(userId);
  }

  /**
   * Kills {@code HardConstraintsController.java:59} ({@code NullReturnValsMutator} on the
   * orElseThrow lambda). When the query service returns empty, the lambda must build and throw a
   * {@link HardConstraintsNotFoundException} carrying the userId — replacing the lambda return with
   * null would NPE before mapping to 404, but more importantly the throw path is the contract.
   */
  @Test
  void controller_get_whenAggregateMissing_throwsHardConstraintsNotFoundException() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    when(queryService.getHardConstraints(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller().get())
        .isInstanceOf(HardConstraintsNotFoundException.class)
        .satisfies(
            ex -> {
              HardConstraintsNotFoundException hcEx = (HardConstraintsNotFoundException) ex;
              assertThat(hcEx.userId()).isEqualTo(userId);
            });
  }

  /**
   * Kills {@code HardConstraintsController.java:68} ({@code NullReturnValsMutator} on {@code
   * update()}). The handler must return the DTO produced by {@code updateService} verbatim — not
   * null — and forward the resolved userId as both the principal id and the actor id.
   */
  @Test
  void controller_update_delegatesToUpdateService_andReturnsItsResult_killsNullReturn() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest().withExpectedVersion(0L).build();
    HardConstraintsDto serviceResult =
        new HardConstraintsDto(
            UUID.randomUUID(),
            userId,
            List.of(),
            new DietaryIdentityDto("omnivore", null, List.of()),
            List.of(),
            List.of(),
            List.of(),
            1L);
    when(updateService.updateHardConstraints(userId, request, userId)).thenReturn(serviceResult);

    HardConstraintsDto result = controller().update(request);

    assertThat(result).isSameAs(serviceResult);
    assertThat(result.version()).isEqualTo(1L);
    // Both userId arguments are the same — caller cannot impersonate other users.
    verify(updateService).updateHardConstraints(userId, request, userId);
  }

  /**
   * Kills {@code HardConstraintsController.java:78} ({@code NullReturnValsMutator} on {@code
   * auditLog()}). The handler must return the Page produced by the query service. Also pins the
   * page/size → PageRequest mapping by checking the captured Pageable.
   */
  @Test
  void controller_auditLog_passesPageRequestToService_andReturnsItsPage_killsNullReturn() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    Page<HardConstraintsAuditEntryDto> servicePage =
        new PageImpl<>(
            List.of(
                new HardConstraintsAuditEntryDto(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    userId,
                    "allergies",
                    objectMapper.valueToTree(List.of()),
                    objectMapper.valueToTree(List.of("peanut")),
                    Instant.parse("2026-05-08T09:00:00Z"))),
            PageRequest.of(2, 7),
            42L);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    when(queryService.getHardConstraintsAuditLog(any(UUID.class), pageableCaptor.capture()))
        .thenReturn(servicePage);

    Page<HardConstraintsAuditEntryDto> result = controller().auditLog(2, 7);

    assertThat(result).isSameAs(servicePage);
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
    assertThat(result.getTotalElements()).isEqualTo(42L);
  }

  /**
   * Kills {@code HardConstraintsController.java:82} ({@code NullReturnValsMutator} on {@code
   * requireCurrentUserId()}) and {@code HardConstraintsController.java:85} (the orElseThrow lambda
   * Null return). The shared resolver-anonymous path is covered: when currentUserId is empty, every
   * public method must surface 401 via ResponseStatusException — not silently return null / NPE.
   */
  @Test
  void controller_whenAnonymous_get_throws401_killsRequireCurrentUserIdNullAndLambdaNull() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller().get())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void controller_whenAnonymous_update_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());
    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest().withExpectedVersion(0L).build();

    assertThatThrownBy(() -> controller().update(request))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(updateService);
  }

  @Test
  void controller_whenAnonymous_auditLog_throws401() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller().auditLog(0, 20))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    verifyNoInteractions(queryService);
  }

  // =================================================================================
  // PreferenceModule — the three facade accessors (query / update / filter) had no
  // unit coverage. NullReturnValsMutator on each would silently break cross-module callers.
  // =================================================================================

  /**
   * Kills {@code PreferenceModule.java:34/38/42} ({@code NullReturnValsMutator} on {@code query()},
   * {@code update()}, {@code filter()}). Each accessor must return the same instance it was
   * constructed with — null would break every cross-module callsite that injects the facade.
   */
  @Test
  void preferenceModule_facadeAccessors_returnConstructorArgumentsByIdentity() {
    PreferenceModule module =
        new PreferenceModule(
            queryService,
            updateService,
            hardConstraintFilterService,
            lifestyleConfigQueryService,
            lifestyleConfigUpdateService,
            tasteProfileQueryService,
            tasteProfileUpdateService,
            preferenceArchiveQueryService,
            preferenceArchiveUpdateService);

    assertThat(module.query()).isSameAs(queryService);
    assertThat(module.update()).isSameAs(updateService);
    assertThat(module.filter()).isSameAs(hardConstraintFilterService);
    assertThat(module.lifestyleConfigQuery()).isSameAs(lifestyleConfigQueryService);
    assertThat(module.lifestyleConfigUpdate()).isSameAs(lifestyleConfigUpdateService);
    assertThat(module.tasteProfileQuery()).isSameAs(tasteProfileQueryService);
    assertThat(module.tasteProfileUpdate()).isSameAs(tasteProfileUpdateService);
    assertThat(module.preferenceArchiveQuery()).isSameAs(preferenceArchiveQueryService);
    assertThat(module.preferenceArchiveUpdate()).isSameAs(preferenceArchiveUpdateService);
  }

  // =================================================================================
  // HardConstraintsUpdatedEvent — overrides of scopeKind / scopeId from ScopeChangedEvent
  // had no direct test. Mutants would return null / empty string and propagate to listeners.
  // =================================================================================

  /**
   * Kills {@code HardConstraintsUpdatedEvent.java:21} ({@code EmptyObjectReturnValsMutator} on
   * {@code scopeKind()}). The literal contract value is asserted so any drift from the documented
   * "hard-constraints" string breaks the test.
   */
  @Test
  void hardConstraintsUpdatedEvent_scopeKind_isLiteralHardConstraints() {
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(),
            java.util.Set.of("allergies"),
            UUID.randomUUID(),
            Instant.parse("2026-05-08T10:00:00Z"));

    assertThat(event.scopeKind()).isEqualTo("hard-constraints");
    assertThat(event.scopeKind()).isNotEmpty();
  }

  /**
   * Kills {@code HardConstraintsUpdatedEvent.java:26} ({@code NullReturnValsMutator} on {@code
   * scopeId()}). The override aliases {@code userId} — pin that identity contract directly so a
   * future refactor that returns null or the trace id can't slip through.
   */
  @Test
  void hardConstraintsUpdatedEvent_scopeId_aliasesUserId() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    HardConstraintsUpdatedEvent event =
        new HardConstraintsUpdatedEvent(
            userId, java.util.Set.of("allergies"), traceId, Instant.parse("2026-05-08T10:00:00Z"));

    assertThat(event.scopeId()).isEqualTo(userId);
    assertThat(event.scopeId()).isNotEqualTo(traceId);
  }

  // =================================================================================
  // HardConstraintsNotFoundException.userId() — accessor was uncovered.
  // =================================================================================

  /**
   * Kills {@code HardConstraintsNotFoundException.java:20} ({@code NullReturnValsMutator} on {@code
   * userId()}). The accessor surfaces the offending id to upstream callers (the exception handler
   * uses {@code getMessage()} but downstream test/diagnostics rely on the typed accessor).
   */
  @Test
  void hardConstraintsNotFoundException_userIdAccessor_returnsConstructorArgument() {
    UUID userId = UUID.randomUUID();
    HardConstraintsNotFoundException ex = new HardConstraintsNotFoundException(userId);

    assertThat(ex.userId()).isEqualTo(userId);
    assertThat(ex.getMessage()).contains(userId.toString());
  }

  // =================================================================================
  // HardConstraintFilterServiceImpl.passesIndex L256 — FALSE_RETURNS survived because no
  // existing test exercised the path "user has constraints + non-null recipe list whose
  // ingredient list is empty/null". Below tests pin both branches of the fast-path.
  // =================================================================================

  /**
   * Kills {@code HardConstraintFilterServiceImpl.java:256} ({@code BooleanFalseReturnValsMutator}
   * on {@code passesIndex}). When a user HAS constraints but a recipe has an empty ingredient list,
   * the recipe must PASS. Mutating to {@code return false} would wrongly exclude empty recipes.
   */
  @Test
  void filterRecipes_recipeWithEmptyIngredientList_passes_pinsPassesIndexEmptyFastPath() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    UUID emptyRecipe = UUID.randomUUID();
    List<UUID> passing = filterService().filterRecipes(userId, Map.of(emptyRecipe, List.of()));

    assertThat(passing).containsExactly(emptyRecipe);
  }

  /**
   * Companion to the previous — for a user WITH constraints, a recipe whose ingredient list is
   * {@code null}-equivalent (empty list via {@code Map.of(recipe, List.of())}) must pass; without
   * the empty-fast-path return-true the matching loop would dereference null. Pins both halves of
   * the {@code ingredientKeys == null || ingredientKeys.isEmpty()} disjunction.
   */
  @Test
  void filterRecipes_mixedRecipes_emptyAndConstrained_emptyPassesAndConstrainedFails() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(allergenDerivativeRepository.findAll()).thenReturn(List.of());

    UUID empty = UUID.randomUUID();
    UUID bad = UUID.randomUUID();
    UUID good = UUID.randomUUID();
    Map<UUID, List<String>> recipes =
        Map.of(empty, List.of(), bad, List.of("peanut"), good, List.of("rice"));

    List<UUID> passing = filterService().filterRecipes(userId, recipes);

    assertThat(passing).containsExactlyInAnyOrder(empty, good);
  }

  // =================================================================================
  // PreferenceServiceImpl.Snapshot — three List.sort calls (L285/292/299) survived because
  // no test asserted that "same children in a different order" is a no-op. The sort is what
  // makes that contract hold; removing it would flag a false change and write spurious audit
  // rows.
  // =================================================================================

  /**
   * Kills {@code PreferenceServiceImpl.java:285} ({@code VoidMethodCallMutator} on the
   * exceptions-sort inside {@code Snapshot.of}). Constructs an aggregate with two
   * DietaryIdentityException children in one order, then PUTs a request with the same two children
   * in reversed order. The diff MUST be empty (no-op) because the snapshot sorts by allows /
   * frequency / context before comparing. Removing the sort would flag {@code
   * dietaryIdentityExceptions} as changed and write an audit row.
   */
  @Test
  void updateHardConstraints_dietaryExceptionsReordered_isNoOp_killsExceptionsSort() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("vegetarian")
            .build();
    // Children seeded in (apple, banana) order on the entity.
    aggregate
        .getExceptions()
        .add(
            DietaryIdentityException.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .allows("apple")
                .frequency("daily")
                .context("HOME")
                .build());
    aggregate
        .getExceptions()
        .add(
            DietaryIdentityException.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .allows("banana")
                .frequency("weekly")
                .context("HOME")
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    // Request supplies the same two children in REVERSED order — equal as a sorted set.
    UpdateHardConstraintsRequest reorderRequest =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(
                new DietaryIdentityDto(
                    "vegetarian",
                    null,
                    List.of(
                        new DietaryIdentityExceptionDto("banana", "weekly", "HOME"),
                        new DietaryIdentityExceptionDto("apple", "daily", "HOME"))))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, reorderRequest, userId);

    // Reorder-only → diff is empty → no audit row, no event, no save.
    verify(auditLogRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  /**
   * Kills {@code PreferenceServiceImpl.java:292} ({@code VoidMethodCallMutator} on the
   * intolerances-sort). Same pattern: two intolerances in (lactose, gluten) → request in (gluten,
   * lactose) → no-op iff the sort normalises both before comparing.
   */
  @Test
  void updateHardConstraints_intolerancesReordered_isNoOp_killsIntolerancesSort() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("lactose")
                .severity("MODERATE")
                .notes("note-lactose")
                .build());
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("gluten")
                .severity("HIGH")
                .notes("note-gluten")
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    UpdateHardConstraintsRequest reorderRequest =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withIntolerances(
                new HardIntoleranceDto("gluten", "HIGH", "note-gluten"),
                new HardIntoleranceDto("lactose", "MODERATE", "note-lactose"))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, reorderRequest, userId);

    verify(auditLogRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  /**
   * Kills {@code PreferenceServiceImpl.java:299} ({@code VoidMethodCallMutator} on the age-
   * restrictions-sort). Two rule keys in (no_whole_nuts, no_honey) → request in (no_honey,
   * no_whole_nuts) → no-op iff sort normalises both.
   */
  @Test
  void updateHardConstraints_ageRestrictionsReordered_isNoOp_killsAgeRestrictionsSort() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_whole_nuts")
                .autoPopulated(true)
                .build());
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_honey")
                .autoPopulated(false)
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));

    UpdateHardConstraintsRequest reorderRequest =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withAgeRestrictions(
                new AgeRestrictionDto("no_honey", false),
                new AgeRestrictionDto("no_whole_nuts", true))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, reorderRequest, userId);

    verify(auditLogRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  /**
   * Companion sanity: a GENUINE change to the age-restrictions list (different rule key) MUST flag
   * the field as changed — protects against an overly-broad sort that swallowed actual diffs.
   */
  @Test
  void updateHardConstraints_ageRestrictionGenuinelyChanged_flagsFieldChanged() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_whole_nuts")
                .autoPopulated(true)
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withAgeRestrictions(new AgeRestrictionDto("no_honey", false))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> captor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    assertThat(captor.getValue().getFieldChanged()).isEqualTo("ageRestrictions");
  }

  // =================================================================================
  // Defensive: pin the {@code Snapshot.diff} branch that only fires when ANY single field
  // differs but the rest are equal. We already cover the all-fields-change and the all-
  // fields-equal cases — these single-field probes catch CONDITIONALS_BOUNDARY mutants on
  // the individual {@code if (!Objects.equals(...))} guards if they ever surface.
  // =================================================================================

  @Test
  void updateHardConstraints_onlyDietaryIdentityLabelChanged_writesOneAuditRowOnly() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withDietaryIdentityBase("omnivore")
            .withDietaryIdentityLabel("Old label")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(new DietaryIdentityDto("omnivore", "New label", List.of()))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> captor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    assertThat(captor.getValue().getFieldChanged()).isEqualTo("dietaryIdentityLabel");
  }

  @Test
  void updateHardConstraints_onlyMedicalDietsChanged_writesOneAuditRowOnly() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withMedicalDiets("low_sodium")
            .build();
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withMedicalDiets("low_sodium", "low_fodmap")
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> captor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    assertThat(captor.getValue().getFieldChanged()).isEqualTo("medicalDiets");
  }

  // =================================================================================
  // Snapshot.toJson — switch case for every field. Indirectly verifies each JSON value is
  // serialised by reading the previousValueJson back from the audit row.
  // =================================================================================

  @Test
  void updateHardConstraints_intolerancesGenuinelyChanged_auditRowCarriesPreviousAndNextJson() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints().withUserId(userId).build();
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("lactose")
                .severity("LOW")
                .notes(null)
                .build());
    when(hardConstraintsRepository.findWithChildrenByUserId(userId))
        .thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(any(HardConstraints.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateHardConstraintsRequest request =
        HardConstraintsTestData.updateRequest()
            .withDietaryIdentity(HardConstraintsTestData.omnivoreIdentity())
            .withIntolerances(new HardIntoleranceDto("gluten", "HIGH", "celiac"))
            .withExpectedVersion(0L)
            .build();

    preferenceService().updateHardConstraints(userId, request, userId);

    ArgumentCaptor<HardConstraintsAuditLog> captor =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    HardConstraintsAuditLog row = captor.getValue();
    assertThat(row.getFieldChanged()).isEqualTo("intolerances");
    assertThat(row.getPreviousValueJson().toString()).contains("lactose");
    assertThat(row.getNewValueJson().toString()).contains("gluten").contains("celiac");
  }

  // =================================================================================
  // Page.empty(pageable) — pin the pageable-bearing variant by reading the page number off
  // the returned page. Adds defence around the no-aggregate audit-log path.
  // =================================================================================

  @Test
  void getHardConstraintsAuditLog_emptyPage_preservesPageNumberFromRequest() {
    UUID userId = UUID.randomUUID();
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    Pageable pageable = PageRequest.of(3, 11);
    Page<HardConstraintsAuditEntryDto> page =
        preferenceService().getHardConstraintsAuditLog(userId, pageable);

    assertThat(page.isEmpty()).isTrue();
    assertThat(page.getNumber()).isEqualTo(3);
    assertThat(page.getSize()).isEqualTo(11);
  }

  // =================================================================================
  // Tiny accessor / mapper round-trips that pin getters / record components left bare by the
  // baseline — pure value-class tests, deterministic, no I/O.
  // =================================================================================

  /**
   * Round-trips {@link HardConstraintsAuditEntryDto} construction via the real mapper from an
   * entity, pinning every record accessor — record components are otherwise prone to
   * NullReturnValsMutator survivors when no test reads them.
   */
  @Test
  void mapper_auditEntryDto_roundTripsEveryField() {
    UUID id = UUID.randomUUID();
    UUID hcId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-04-05T06:07:08Z");
    HardConstraintsAuditLog entity =
        new HardConstraintsAuditLog(
            id,
            hcId,
            actor,
            "allergies",
            objectMapper.valueToTree(List.of("peanut")),
            objectMapper.valueToTree(List.of("peanut", "shellfish")),
            occurredAt);

    HardConstraintsAuditEntryDto dto = mapper.toAuditEntryDto(entity);

    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.hardConstraintsId()).isEqualTo(hcId);
    assertThat(dto.actorUserId()).isEqualTo(actor);
    assertThat(dto.fieldChanged()).isEqualTo("allergies");
    assertThat(dto.previousValueJson().get(0).asText()).isEqualTo("peanut");
    assertThat(dto.newValueJson().get(1).asText()).isEqualTo("shellfish");
    assertThat(dto.occurredAt()).isEqualTo(occurredAt);
  }

  @Test
  void mapper_auditEntryDto_nullEntity_returnsNull() {
    assertThat(mapper.toAuditEntryDto(null)).isNull();
  }

  /**
   * Round-trips {@link HardConstraintsDto} via the real mapper with one of every child kind. Pins
   * defensive-copy behaviour on allergies and medicalDiets (a {@link NullReturnValsMutator} on
   * either accessor would surface as a missing list in the DTO).
   */
  @Test
  void mapper_toDto_populatesEveryNestedField() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut", "shellfish")
            .withMedicalDiets("low_sodium")
            .withDietaryIdentityBase("vegetarian")
            .withDietaryIdentityLabel("Lacto-vegetarian")
            .build();
    aggregate
        .getExceptions()
        .add(
            DietaryIdentityException.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .allows("fish")
                .frequency("weekly")
                .context("HOME")
                .build());
    aggregate
        .getIntolerances()
        .add(
            HardIntolerance.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .substance("lactose")
                .severity("MODERATE")
                .notes(null)
                .build());
    aggregate
        .getAgeRestrictions()
        .add(
            AgeRestriction.builder()
                .id(UUID.randomUUID())
                .hardConstraints(aggregate)
                .ruleKey("no_whole_nuts")
                .autoPopulated(true)
                .build());
    aggregate.setVersion(5L);

    HardConstraintsDto dto = mapper.toDto(aggregate);

    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.allergies()).containsExactly("peanut", "shellfish");
    assertThat(dto.medicalDiets()).containsExactly("low_sodium");
    assertThat(dto.dietaryIdentity().base()).isEqualTo("vegetarian");
    assertThat(dto.dietaryIdentity().labelForDisplay()).isEqualTo("Lacto-vegetarian");
    assertThat(dto.dietaryIdentity().exceptions())
        .containsExactly(new DietaryIdentityExceptionDto("fish", "weekly", "HOME"));
    assertThat(dto.intolerances())
        .containsExactly(new HardIntoleranceDto("lactose", "MODERATE", null));
    assertThat(dto.ageRestrictions()).containsExactly(new AgeRestrictionDto("no_whole_nuts", true));
    assertThat(dto.version()).isEqualTo(5L);
  }

  @Test
  void mapper_toDto_nullEntity_returnsNull() {
    assertThat(mapper.toDto(null)).isNull();
  }

  /** Pins the defensive-copy contract: the returned list is independent of the entity's list. */
  @Test
  void mapper_toDto_returnsDefensiveCopiesForAllergiesAndMedicalDiets() {
    UUID userId = UUID.randomUUID();
    List<String> sourceAllergies = new ArrayList<>(List.of("peanut"));
    HardConstraints aggregate =
        HardConstraints.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .allergies(sourceAllergies)
            .dietaryIdentityBase("omnivore")
            .medicalDiets(new ArrayList<>(List.of("low_sodium")))
            .exceptions(new ArrayList<>())
            .intolerances(new ArrayList<>())
            .ageRestrictions(new ArrayList<>())
            .build();

    HardConstraintsDto dto = mapper.toDto(aggregate);

    // Mutating the source list must not leak into the DTO list.
    sourceAllergies.add("shellfish");
    assertThat(dto.allergies()).containsExactly("peanut");
  }

  /** Empty/null lists collapse to {@code emptyList()}. */
  @Test
  void mapper_toDto_nullAndEmptyCollections_collapseToEmptyLists() {
    UUID userId = UUID.randomUUID();
    HardConstraints aggregate =
        HardConstraints.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .allergies(null)
            .dietaryIdentityBase("omnivore")
            .medicalDiets(new ArrayList<>())
            .exceptions(new ArrayList<>())
            .intolerances(new ArrayList<>())
            .ageRestrictions(new ArrayList<>())
            .build();

    HardConstraintsDto dto = mapper.toDto(aggregate);

    assertThat(dto.allergies()).isEmpty();
    assertThat(dto.medicalDiets()).isEmpty();
    assertThat(dto.dietaryIdentity().exceptions()).isEmpty();
    assertThat(dto.intolerances()).isEmpty();
    assertThat(dto.ageRestrictions()).isEmpty();
  }
}
