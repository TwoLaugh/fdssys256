package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.AcceptDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.ActivityAdjustmentDto;
import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.api.dto.FloorViolationDto;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.HealthDirectiveDto;
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupResultDto;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.api.dto.IntakeAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeListFilter;
import com.example.mealprep.nutrition.api.dto.IntakeSlotSearchResultDto;
import com.example.mealprep.nutrition.api.dto.JournalAction;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.api.dto.RejectDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UnmappedIngredientDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.api.dto.WeeklyAggregateDto;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.ActivityAdjustment;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.DailyActivityLog;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.FoodMoodJournalEntry;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.event.FoodMoodEntryWrittenEvent;
import com.example.mealprep.nutrition.event.HealthDirectiveAcceptedEvent;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.IngredientMappingCorrectedEvent;
import com.example.mealprep.nutrition.event.IntakeLoggedEvent;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.DuplicateHealthDirectiveException;
import com.example.mealprep.nutrition.exception.HealthDirectiveAlreadyDecidedException;
import com.example.mealprep.nutrition.exception.HealthDirectiveNotFoundException;
import com.example.mealprep.nutrition.exception.HealthDirectiveSafetyGateBlockedException;
import com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSlotNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSnackNotFoundException;
import com.example.mealprep.nutrition.exception.InvalidIntakeRangeException;
import com.example.mealprep.nutrition.exception.InvalidPlanRollupException;
import com.example.mealprep.nutrition.exception.InvalidWeekStartException;
import com.example.mealprep.nutrition.exception.JournalEntryNotFoundException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link NutritionQueryService} and {@link NutritionUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED. The aggregate has three list
 * children — multi-attribute {@code @EntityGraph} would throw {@code MultipleBagFetchException}, so
 * {@code getTargets} touches each list inside the transaction to force lazy load (4 SELECTs: root +
 * three list bags; the {@code @OneToOne} eating window joins on the root SELECT).
 *
 * <p>The update path is field-level diffing — one audit row per genuinely changed field; if the
 * request is a no-op, no rows are written and no event is published. Field paths use dotted
 * notation (e.g. {@code "calorieTarget"}, {@code "protein.targetG"}, {@code
 * "perMealDistribution"}).
 */
@Service
public class NutritionServiceImpl
    implements NutritionQueryService,
        NutritionUpdateService,
        NutritionCalculationService,
        NutritionFloorGateService {

  private static final Logger log = LoggerFactory.getLogger(NutritionServiceImpl.class);

  // Field paths — kept as constants so the audit-log queries are searchable by literal.
  static final String FIELD_GOAL = "goal";
  static final String FIELD_DAILY_CALORIE_TARGET = "calories.dailyTarget";
  static final String FIELD_CALORIE_TOLERANCE_UNDER = "calories.toleranceUnder";
  static final String FIELD_CALORIE_TOLERANCE_OVER = "calories.toleranceOver";
  static final String FIELD_CALORIE_ENFORCEMENT = "calories.enforcement";
  static final String FIELD_CALORIE_DIRECTION = "calories.direction";
  static final String FIELD_PROTEIN_TARGET = "protein.targetG";
  static final String FIELD_PROTEIN_FLOOR = "protein.floorG";
  static final String FIELD_PROTEIN_ENFORCEMENT = "protein.enforcement";
  static final String FIELD_PROTEIN_DIRECTION = "protein.direction";
  static final String FIELD_CARBS_TARGET = "carbs.targetG";
  static final String FIELD_CARBS_FLOOR = "carbs.floorG";
  static final String FIELD_CARBS_ENFORCEMENT = "carbs.enforcement";
  static final String FIELD_CARBS_DIRECTION = "carbs.direction";
  static final String FIELD_FAT_TARGET = "fat.targetG";
  static final String FIELD_FAT_FLOOR = "fat.floorG";
  static final String FIELD_FAT_ENFORCEMENT = "fat.enforcement";
  static final String FIELD_FAT_DIRECTION = "fat.direction";
  static final String FIELD_FIBRE_TARGET = "fibre.targetG";
  static final String FIELD_FIBRE_FLOOR = "fibre.floorG";
  static final String FIELD_FIBRE_ENFORCEMENT = "fibre.enforcement";
  static final String FIELD_FIBRE_DIRECTION = "fibre.direction";
  static final String FIELD_SAT_FAT_TARGET = "satFat.targetG";
  static final String FIELD_SAT_FAT_DIRECTION = "satFat.direction";
  static final String FIELD_NOTES = "notes";
  static final String FIELD_PER_MEAL_DISTRIBUTION = "perMealDistribution";
  static final String FIELD_MICRO_TARGETS = "microTargets";
  static final String FIELD_EATING_WINDOW = "eatingWindow";
  static final String FIELD_ACTIVITY_ADJUSTMENTS = "activityAdjustments";

  private static final int RANGE_MAX_DAYS = 35;

  private final NutritionTargetsRepository targetsRepository;
  private final NutritionTargetsAuditRepository auditRepository;
  private final IntakeDayRepository intakeDayRepository;
  private final IntakeAuditRepository intakeAuditRepository;
  private final DailyActivityLogRepository dailyActivityLogRepository;
  private final FoodMoodJournalRepository journalRepository;
  private final IngredientMappingRepository ingredientMappingRepository;
  private final HealthDirectiveRepository healthDirectiveRepository;
  private final TargetsMapper mapper;
  private final IntakeMapper intakeMapper;
  private final DailyActivityMapper dailyActivityMapper;
  private final JournalMapper journalMapper;
  private final IngredientMappingMapper ingredientMappingMapper;
  private final HealthDirectiveMapper healthDirectiveMapper;
  private final IntakeKeyNormaliser intakeKeyNormaliser;
  private final DirectiveSafetyGate directiveSafetyGate;
  private final DirectiveApplier directiveApplier;
  private final IntakeAggregator intakeAggregator;
  private final DivergenceDetector divergenceDetector;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public NutritionServiceImpl(
      NutritionTargetsRepository targetsRepository,
      NutritionTargetsAuditRepository auditRepository,
      IntakeDayRepository intakeDayRepository,
      IntakeAuditRepository intakeAuditRepository,
      DailyActivityLogRepository dailyActivityLogRepository,
      FoodMoodJournalRepository journalRepository,
      IngredientMappingRepository ingredientMappingRepository,
      HealthDirectiveRepository healthDirectiveRepository,
      TargetsMapper mapper,
      IntakeMapper intakeMapper,
      DailyActivityMapper dailyActivityMapper,
      JournalMapper journalMapper,
      IngredientMappingMapper ingredientMappingMapper,
      HealthDirectiveMapper healthDirectiveMapper,
      IntakeKeyNormaliser intakeKeyNormaliser,
      DirectiveSafetyGate directiveSafetyGate,
      DirectiveApplier directiveApplier,
      IntakeAggregator intakeAggregator,
      DivergenceDetector divergenceDetector,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.targetsRepository = targetsRepository;
    this.auditRepository = auditRepository;
    this.intakeDayRepository = intakeDayRepository;
    this.intakeAuditRepository = intakeAuditRepository;
    this.dailyActivityLogRepository = dailyActivityLogRepository;
    this.journalRepository = journalRepository;
    this.ingredientMappingRepository = ingredientMappingRepository;
    this.healthDirectiveRepository = healthDirectiveRepository;
    this.mapper = mapper;
    this.intakeMapper = intakeMapper;
    this.dailyActivityMapper = dailyActivityMapper;
    this.journalMapper = journalMapper;
    this.ingredientMappingMapper = ingredientMappingMapper;
    this.healthDirectiveMapper = healthDirectiveMapper;
    this.intakeKeyNormaliser = intakeKeyNormaliser;
    this.directiveSafetyGate = directiveSafetyGate;
    this.directiveApplier = directiveApplier;
    this.intakeAggregator = intakeAggregator;
    this.divergenceDetector = divergenceDetector;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<TargetsDto> getTargets(UUID userId) {
    return targetsRepository
        .findByUserId(userId)
        .map(
            entity -> {
              // Force lazy-load of the three @OneToMany list children inside the read-only tx.
              // The @OneToOne eatingWindow is fetched as part of the root SELECT.
              entity.getPerMealDistribution().size();
              entity.getMicroTargets().size();
              entity.getActivityAdjustments().size();
              return mapper.toDto(entity);
            });
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> getUserIdsWithTargets() {
    return targetsRepository.findDistinctUserIds();
  }

  @Override
  @Transactional(readOnly = true)
  public Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable) {
    Optional<NutritionTargets> aggregate = targetsRepository.findByUserId(userId);
    if (aggregate.isEmpty()) {
      return Page.empty(pageable);
    }
    return auditRepository
        .findByTargetsIdOrderByOccurredAtDesc(aggregate.get().getId(), pageable)
        .map(mapper::toAuditEntryDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public TargetsDto updateTargets(UUID userId, UpdateTargetsRequest request, UUID actorUserId) {
    NutritionTargets aggregate =
        targetsRepository
            .findByUserId(userId)
            .orElseThrow(() -> new NutritionTargetsNotFoundException(userId));

    // Force lazy-load before snapshotting — diffs on collections need the full child set.
    aggregate.getPerMealDistribution().size();
    aggregate.getMicroTargets().size();
    aggregate.getActivityAdjustments().size();
    // Touch eatingWindow so the lazy proxy is initialised before the snapshot reads it.
    @SuppressWarnings("unused")
    EatingWindow ew = aggregate.getEatingWindow();

    // Optimistic-lock pre-check — same approach as PreferenceServiceImpl.updateHardConstraints.
    if (aggregate.getVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          NutritionTargets.class, aggregate.getId());
    }

    // Compute the diff WITHOUT mutating the aggregate first. Mutating before diffing causes
    // Hibernate to flush the orphan-removed children's INSERTs before the DELETEs within the
    // same flush — which collides with the (targets_id, business_key) unique indices on the
    // child tables when the request happens to be a no-op (or even partially overlapping).
    Snapshot before = Snapshot.of(aggregate);
    Snapshot after = Snapshot.fromRequest(request);
    Set<String> changedFields = before.diff(after);

    if (changedFields.isEmpty()) {
      log.info(
          "nutrition targets PUT was a no-op userId={} version={}", userId, aggregate.getVersion());
      return mapper.toDto(aggregate);
    }

    // Apply scalar fields.
    aggregate.setGoal(request.goal());
    applyCalories(aggregate, request.calories());
    applyMacro(aggregate, "protein", request.protein());
    applyMacro(aggregate, "carbs", request.carbs());
    applyMacro(aggregate, "fat", request.fat());
    applyMacro(aggregate, "fibre", request.fibre());
    aggregate.setSatFatTargetG(request.satFat().targetG());
    aggregate.setSatFatDirection(request.satFat().direction());
    aggregate.setNotes(request.notes());

    // Replace child collections — cascade + orphanRemoval handle delete + insert.
    aggregate.replacePerMealDistribution(toPerMealEntities(request.perMealDistribution()));
    aggregate.replaceMicroTargets(toMicroTargetEntities(request.microTargets()));
    aggregate.replaceActivityAdjustments(toActivityEntities(request.activityAdjustments()));
    aggregate.replaceEatingWindow(toEatingWindowEntity(request.eatingWindow()));

    Instant now = Instant.now(clock);
    writeAuditRows(aggregate.getId(), actorUserId, changedFields, before, after, now);

    // saveAndFlush so the @Version bump materialises before we map to DTO; otherwise the response
    // carries the stale version. Same trick as PreferenceServiceImpl.updateHardConstraints and
    // HouseholdServiceImpl.createHousehold.
    NutritionTargets saved = targetsRepository.saveAndFlush(aggregate);
    eventPublisher.publishEvent(
        new NutritionTargetsChangedEvent(
            userId, saved.getId(), changedFields, UUID.randomUUID(), now));
    log.info(
        "nutrition targets updated userId={} fieldsChanged={} version={}",
        userId,
        changedFields,
        saved.getVersion());
    return mapper.toDto(saved);
  }

  // ---------------- Apply helpers ----------------

  private static void applyCalories(NutritionTargets aggregate, CalorieTargetDto cals) {
    aggregate.setDailyCalorieTarget(cals.dailyTarget());
    aggregate.setCalorieToleranceUnder(cals.toleranceUnder());
    aggregate.setCalorieToleranceOver(cals.toleranceOver());
    aggregate.setCalorieEnforcement(cals.enforcement());
    aggregate.setCalorieDirection(cals.direction());
  }

  private static void applyMacro(NutritionTargets aggregate, String macro, MacroTargetDto m) {
    switch (macro) {
      case "protein" -> {
        aggregate.setProteinTargetG(m.targetG());
        aggregate.setProteinFloorG(m.floorG());
        aggregate.setProteinEnforcement(m.enforcement());
        aggregate.setProteinDirection(m.direction());
      }
      case "carbs" -> {
        aggregate.setCarbsTargetG(m.targetG());
        aggregate.setCarbsFloorG(m.floorG());
        aggregate.setCarbsEnforcement(m.enforcement());
        aggregate.setCarbsDirection(m.direction());
      }
      case "fat" -> {
        aggregate.setFatTargetG(m.targetG());
        aggregate.setFatFloorG(m.floorG());
        aggregate.setFatEnforcement(m.enforcement());
        aggregate.setFatDirection(m.direction());
      }
      case "fibre" -> {
        aggregate.setFibreTargetG(m.targetG());
        aggregate.setFibreFloorG(m.floorG());
        aggregate.setFibreEnforcement(m.enforcement());
        aggregate.setFibreDirection(m.direction());
      }
      default -> throw new IllegalStateException("Unknown macro: " + macro);
    }
  }

  // ---------------- Entity construction ----------------

  private static List<PerMealDistributionEntry> toPerMealEntities(List<PerMealDistributionDto> in) {
    if (in == null || in.isEmpty()) {
      return Collections.emptyList();
    }
    List<PerMealDistributionEntry> out = new ArrayList<>(in.size());
    for (PerMealDistributionDto dto : in) {
      out.add(
          PerMealDistributionEntry.builder()
              .id(UUID.randomUUID())
              .mealSlot(dto.mealSlot())
              .calorieTarget(dto.calorieTarget())
              .proteinTargetG(dto.proteinTargetG())
              .build());
    }
    return out;
  }

  private static List<MicroTarget> toMicroTargetEntities(List<MicroTargetDto> in) {
    if (in == null || in.isEmpty()) {
      return Collections.emptyList();
    }
    List<MicroTarget> out = new ArrayList<>(in.size());
    for (MicroTargetDto dto : in) {
      out.add(
          MicroTarget.builder()
              .id(UUID.randomUUID())
              .nutrientKey(dto.nutrientKey())
              .targetValue(dto.targetValue())
              .upperLimit(dto.upperLimit())
              .sourcePreference(dto.sourcePreference())
              .notes(dto.notes())
              .build());
    }
    return out;
  }

  private static List<ActivityAdjustment> toActivityEntities(List<ActivityAdjustmentDto> in) {
    if (in == null || in.isEmpty()) {
      return Collections.emptyList();
    }
    List<ActivityAdjustment> out = new ArrayList<>(in.size());
    for (ActivityAdjustmentDto dto : in) {
      out.add(
          ActivityAdjustment.builder()
              .id(UUID.randomUUID())
              .activityLevel(dto.activityLevel())
              .calorieModifier(dto.calorieModifier())
              .carbModifierG(dto.carbModifierG())
              .build());
    }
    return out;
  }

  private static EatingWindow toEatingWindowEntity(EatingWindowDto dto) {
    if (dto == null) {
      return null;
    }
    return EatingWindow.builder()
        .id(UUID.randomUUID())
        .enabled(dto.enabled())
        .windowStart(dto.windowStart())
        .windowEnd(dto.windowEnd())
        .notes(dto.notes())
        .build();
  }

  // ---------------- Audit ----------------

  private void writeAuditRows(
      UUID targetsId,
      UUID actorUserId,
      Set<String> changedFields,
      Snapshot before,
      Snapshot after,
      Instant now) {
    for (String field : changedFields) {
      JsonNode previous = before.toJson(field, objectMapper);
      JsonNode next = after.toJson(field, objectMapper);
      auditRepository.save(
          new NutritionTargetsAuditLog(
              UUID.randomUUID(),
              targetsId,
              actorUserId,
              ActorKind.USER,
              null,
              field,
              previous,
              next,
              now));
    }
  }

  // ---------------- Snapshot ----------------

  /**
   * Snapshot of an aggregate's diff-relevant fields. Children are projected into immutable DTO
   * lists ordered by natural key so reorder-only changes are no-ops.
   */
  private record Snapshot(
      Goal goal,
      int dailyCalorieTarget,
      int calorieToleranceUnder,
      int calorieToleranceOver,
      String calorieEnforcement,
      Object calorieDirection,
      BigDecimal proteinTargetG,
      BigDecimal proteinFloorG,
      String proteinEnforcement,
      Object proteinDirection,
      BigDecimal carbsTargetG,
      BigDecimal carbsFloorG,
      String carbsEnforcement,
      Object carbsDirection,
      BigDecimal fatTargetG,
      BigDecimal fatFloorG,
      String fatEnforcement,
      Object fatDirection,
      BigDecimal fibreTargetG,
      BigDecimal fibreFloorG,
      String fibreEnforcement,
      Object fibreDirection,
      BigDecimal satFatTargetG,
      Object satFatDirection,
      String notes,
      List<PerMealDistributionDto> perMeal,
      List<MicroTargetDto> micros,
      EatingWindowDto eatingWindow,
      List<ActivityAdjustmentDto> activities) {

    static Snapshot of(NutritionTargets a) {
      return new Snapshot(
          a.getGoal(),
          a.getDailyCalorieTarget(),
          a.getCalorieToleranceUnder(),
          a.getCalorieToleranceOver(),
          a.getCalorieEnforcement(),
          a.getCalorieDirection(),
          a.getProteinTargetG(),
          a.getProteinFloorG(),
          a.getProteinEnforcement(),
          a.getProteinDirection(),
          a.getCarbsTargetG(),
          a.getCarbsFloorG(),
          a.getCarbsEnforcement(),
          a.getCarbsDirection(),
          a.getFatTargetG(),
          a.getFatFloorG(),
          a.getFatEnforcement(),
          a.getFatDirection(),
          a.getFibreTargetG(),
          a.getFibreFloorG(),
          a.getFibreEnforcement(),
          a.getFibreDirection(),
          a.getSatFatTargetG(),
          a.getSatFatDirection(),
          a.getNotes(),
          perMealAsDtos(a),
          microsAsDtos(a),
          eatingWindowAsDto(a),
          activitiesAsDtos(a));
    }

    /**
     * Project an incoming request into a Snapshot for diff. Used to detect no-op PUTs without first
     * mutating the aggregate (which would trigger orphan-removal flush ordering issues against the
     * child unique indices).
     */
    static Snapshot fromRequest(UpdateTargetsRequest req) {
      List<PerMealDistributionDto> pm =
          sortedCopy(
              req.perMealDistribution(),
              java.util.Comparator.comparing(
                  PerMealDistributionDto::mealSlot,
                  java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
      List<MicroTargetDto> mt =
          sortedCopy(
              req.microTargets(),
              java.util.Comparator.comparing(
                  MicroTargetDto::nutrientKey, java.util.Comparator.nullsFirst(String::compareTo)));
      List<ActivityAdjustmentDto> aa =
          sortedCopy(
              req.activityAdjustments(),
              java.util.Comparator.comparing(
                  ActivityAdjustmentDto::activityLevel,
                  java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
      return new Snapshot(
          req.goal(),
          req.calories().dailyTarget(),
          req.calories().toleranceUnder(),
          req.calories().toleranceOver(),
          req.calories().enforcement(),
          req.calories().direction(),
          req.protein().targetG(),
          req.protein().floorG(),
          req.protein().enforcement(),
          req.protein().direction(),
          req.carbs().targetG(),
          req.carbs().floorG(),
          req.carbs().enforcement(),
          req.carbs().direction(),
          req.fat().targetG(),
          req.fat().floorG(),
          req.fat().enforcement(),
          req.fat().direction(),
          req.fibre().targetG(),
          req.fibre().floorG(),
          req.fibre().enforcement(),
          req.fibre().direction(),
          req.satFat().targetG(),
          req.satFat().direction(),
          req.notes(),
          pm,
          mt,
          req.eatingWindow(),
          aa);
    }

    private static <T> List<T> sortedCopy(List<T> in, java.util.Comparator<T> order) {
      if (in == null || in.isEmpty()) {
        return Collections.emptyList();
      }
      List<T> copy = new ArrayList<>(in);
      copy.sort(order);
      return copy;
    }

    Set<String> diff(Snapshot other) {
      Set<String> changed = new LinkedHashSet<>();
      if (!Objects.equals(goal, other.goal)) {
        changed.add(FIELD_GOAL);
      }
      if (dailyCalorieTarget != other.dailyCalorieTarget) {
        changed.add(FIELD_DAILY_CALORIE_TARGET);
      }
      if (calorieToleranceUnder != other.calorieToleranceUnder) {
        changed.add(FIELD_CALORIE_TOLERANCE_UNDER);
      }
      if (calorieToleranceOver != other.calorieToleranceOver) {
        changed.add(FIELD_CALORIE_TOLERANCE_OVER);
      }
      if (!Objects.equals(calorieEnforcement, other.calorieEnforcement)) {
        changed.add(FIELD_CALORIE_ENFORCEMENT);
      }
      if (!Objects.equals(calorieDirection, other.calorieDirection)) {
        changed.add(FIELD_CALORIE_DIRECTION);
      }
      // Protein
      if (!bigEq(proteinTargetG, other.proteinTargetG)) {
        changed.add(FIELD_PROTEIN_TARGET);
      }
      if (!bigEq(proteinFloorG, other.proteinFloorG)) {
        changed.add(FIELD_PROTEIN_FLOOR);
      }
      if (!Objects.equals(proteinEnforcement, other.proteinEnforcement)) {
        changed.add(FIELD_PROTEIN_ENFORCEMENT);
      }
      if (!Objects.equals(proteinDirection, other.proteinDirection)) {
        changed.add(FIELD_PROTEIN_DIRECTION);
      }
      // Carbs
      if (!bigEq(carbsTargetG, other.carbsTargetG)) {
        changed.add(FIELD_CARBS_TARGET);
      }
      if (!bigEq(carbsFloorG, other.carbsFloorG)) {
        changed.add(FIELD_CARBS_FLOOR);
      }
      if (!Objects.equals(carbsEnforcement, other.carbsEnforcement)) {
        changed.add(FIELD_CARBS_ENFORCEMENT);
      }
      if (!Objects.equals(carbsDirection, other.carbsDirection)) {
        changed.add(FIELD_CARBS_DIRECTION);
      }
      // Fat
      if (!bigEq(fatTargetG, other.fatTargetG)) {
        changed.add(FIELD_FAT_TARGET);
      }
      if (!bigEq(fatFloorG, other.fatFloorG)) {
        changed.add(FIELD_FAT_FLOOR);
      }
      if (!Objects.equals(fatEnforcement, other.fatEnforcement)) {
        changed.add(FIELD_FAT_ENFORCEMENT);
      }
      if (!Objects.equals(fatDirection, other.fatDirection)) {
        changed.add(FIELD_FAT_DIRECTION);
      }
      // Fibre
      if (!bigEq(fibreTargetG, other.fibreTargetG)) {
        changed.add(FIELD_FIBRE_TARGET);
      }
      if (!bigEq(fibreFloorG, other.fibreFloorG)) {
        changed.add(FIELD_FIBRE_FLOOR);
      }
      if (!Objects.equals(fibreEnforcement, other.fibreEnforcement)) {
        changed.add(FIELD_FIBRE_ENFORCEMENT);
      }
      if (!Objects.equals(fibreDirection, other.fibreDirection)) {
        changed.add(FIELD_FIBRE_DIRECTION);
      }
      // Sat fat
      if (!bigEq(satFatTargetG, other.satFatTargetG)) {
        changed.add(FIELD_SAT_FAT_TARGET);
      }
      if (!Objects.equals(satFatDirection, other.satFatDirection)) {
        changed.add(FIELD_SAT_FAT_DIRECTION);
      }
      if (!Objects.equals(notes, other.notes)) {
        changed.add(FIELD_NOTES);
      }
      if (!perMealEq(perMeal, other.perMeal)) {
        changed.add(FIELD_PER_MEAL_DISTRIBUTION);
      }
      if (!microsEq(micros, other.micros)) {
        changed.add(FIELD_MICRO_TARGETS);
      }
      if (!eatingWindowEq(eatingWindow, other.eatingWindow)) {
        changed.add(FIELD_EATING_WINDOW);
      }
      if (!Objects.equals(activities, other.activities)) {
        changed.add(FIELD_ACTIVITY_ADJUSTMENTS);
      }
      return changed;
    }

    private static boolean perMealEq(
        List<PerMealDistributionDto> a, List<PerMealDistributionDto> b) {
      if (a == null || b == null) {
        return a == b;
      }
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        PerMealDistributionDto ai = a.get(i);
        PerMealDistributionDto bi = b.get(i);
        if (!Objects.equals(ai.mealSlot(), bi.mealSlot())
            || ai.calorieTarget() != bi.calorieTarget()
            || !bigEq(ai.proteinTargetG(), bi.proteinTargetG())) {
          return false;
        }
      }
      return true;
    }

    private static boolean microsEq(List<MicroTargetDto> a, List<MicroTargetDto> b) {
      if (a == null || b == null) {
        return a == b;
      }
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        MicroTargetDto ai = a.get(i);
        MicroTargetDto bi = b.get(i);
        if (!Objects.equals(ai.nutrientKey(), bi.nutrientKey())
            || !bigEq(ai.targetValue(), bi.targetValue())
            || !bigEq(ai.upperLimit(), bi.upperLimit())
            || !Objects.equals(ai.sourcePreference(), bi.sourcePreference())
            || !Objects.equals(ai.notes(), bi.notes())) {
          return false;
        }
      }
      return true;
    }

    private static boolean eatingWindowEq(EatingWindowDto a, EatingWindowDto b) {
      if (a == null || b == null) {
        return a == b;
      }
      return a.enabled() == b.enabled()
          && Objects.equals(a.windowStart(), b.windowStart())
          && Objects.equals(a.windowEnd(), b.windowEnd())
          && Objects.equals(a.notes(), b.notes());
    }

    /** BigDecimal comparison ignoring scale ({@code 1.0} equals {@code 1}). */
    private static boolean bigEq(BigDecimal a, BigDecimal b) {
      if (a == null || b == null) {
        return a == b;
      }
      return a.compareTo(b) == 0;
    }

    JsonNode toJson(String field, ObjectMapper objectMapper) {
      Object value =
          switch (field) {
            case FIELD_GOAL -> goal;
            case FIELD_DAILY_CALORIE_TARGET -> dailyCalorieTarget;
            case FIELD_CALORIE_TOLERANCE_UNDER -> calorieToleranceUnder;
            case FIELD_CALORIE_TOLERANCE_OVER -> calorieToleranceOver;
            case FIELD_CALORIE_ENFORCEMENT -> calorieEnforcement;
            case FIELD_CALORIE_DIRECTION -> calorieDirection;
            case FIELD_PROTEIN_TARGET -> proteinTargetG;
            case FIELD_PROTEIN_FLOOR -> proteinFloorG;
            case FIELD_PROTEIN_ENFORCEMENT -> proteinEnforcement;
            case FIELD_PROTEIN_DIRECTION -> proteinDirection;
            case FIELD_CARBS_TARGET -> carbsTargetG;
            case FIELD_CARBS_FLOOR -> carbsFloorG;
            case FIELD_CARBS_ENFORCEMENT -> carbsEnforcement;
            case FIELD_CARBS_DIRECTION -> carbsDirection;
            case FIELD_FAT_TARGET -> fatTargetG;
            case FIELD_FAT_FLOOR -> fatFloorG;
            case FIELD_FAT_ENFORCEMENT -> fatEnforcement;
            case FIELD_FAT_DIRECTION -> fatDirection;
            case FIELD_FIBRE_TARGET -> fibreTargetG;
            case FIELD_FIBRE_FLOOR -> fibreFloorG;
            case FIELD_FIBRE_ENFORCEMENT -> fibreEnforcement;
            case FIELD_FIBRE_DIRECTION -> fibreDirection;
            case FIELD_SAT_FAT_TARGET -> satFatTargetG;
            case FIELD_SAT_FAT_DIRECTION -> satFatDirection;
            case FIELD_NOTES -> notes;
            case FIELD_PER_MEAL_DISTRIBUTION -> perMeal;
            case FIELD_MICRO_TARGETS -> micros;
            case FIELD_EATING_WINDOW -> eatingWindow;
            case FIELD_ACTIVITY_ADJUSTMENTS -> activities;
            default -> throw new IllegalStateException("Unknown field: " + field);
          };
      return objectMapper.valueToTree(value);
    }

    // ---------------- Snapshot helpers ----------------

    private static List<PerMealDistributionDto> perMealAsDtos(NutritionTargets a) {
      if (a.getPerMealDistribution() == null) {
        return Collections.emptyList();
      }
      List<PerMealDistributionDto> result = new ArrayList<>(a.getPerMealDistribution().size());
      for (PerMealDistributionEntry e : a.getPerMealDistribution()) {
        result.add(
            new PerMealDistributionDto(
                e.getMealSlot(), e.getCalorieTarget(), e.getProteinTargetG()));
      }
      result.sort(
          java.util.Comparator.comparing(
              PerMealDistributionDto::mealSlot,
              java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
      return result;
    }

    private static List<MicroTargetDto> microsAsDtos(NutritionTargets a) {
      if (a.getMicroTargets() == null) {
        return Collections.emptyList();
      }
      List<MicroTargetDto> result = new ArrayList<>(a.getMicroTargets().size());
      for (MicroTarget m : a.getMicroTargets()) {
        result.add(
            new MicroTargetDto(
                m.getNutrientKey(),
                m.getTargetValue(),
                m.getUpperLimit(),
                m.getSourcePreference(),
                m.getNotes()));
      }
      result.sort(
          java.util.Comparator.comparing(
              MicroTargetDto::nutrientKey, java.util.Comparator.nullsFirst(String::compareTo)));
      return result;
    }

    private static EatingWindowDto eatingWindowAsDto(NutritionTargets a) {
      EatingWindow w = a.getEatingWindow();
      if (w == null) {
        return null;
      }
      return new EatingWindowDto(w.isEnabled(), w.getWindowStart(), w.getWindowEnd(), w.getNotes());
    }

    private static List<ActivityAdjustmentDto> activitiesAsDtos(NutritionTargets a) {
      if (a.getActivityAdjustments() == null) {
        return Collections.emptyList();
      }
      List<ActivityAdjustmentDto> result = new ArrayList<>(a.getActivityAdjustments().size());
      for (ActivityAdjustment x : a.getActivityAdjustments()) {
        result.add(
            new ActivityAdjustmentDto(
                x.getActivityLevel(), x.getCalorieModifier(), x.getCarbModifierG()));
      }
      result.sort(
          java.util.Comparator.comparing(
              ActivityAdjustmentDto::activityLevel,
              java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
      return result;
    }
  }

  // =================================================================================
  // Intake — query (01b)
  // =================================================================================

  @Override
  @Transactional(readOnly = true)
  public Optional<IntakeDayDto> getIntakeForDay(UUID userId, LocalDate onDate) {
    return intakeDayRepository
        .findByUserIdAndOnDate(userId, onDate)
        .map(
            day -> {
              // Force lazy load of slots + snacks inside the read-only tx (audit log lazy-loads
              // separately on its own paginated query, so we don't touch it here).
              day.getSlots().size();
              day.getSnacks().size();
              return intakeMapper.toDto(day);
            });
  }

  @Override
  @Transactional(readOnly = true)
  public List<IntakeDayDto> getIntakeRange(UUID userId, LocalDate from, LocalDate to) {
    validateRange(from, to);
    List<IntakeDay> days = intakeDayRepository.findByUserIdAndOnDateBetween(userId, from, to);
    List<IntakeDayDto> out = new ArrayList<>(days.size());
    for (IntakeDay day : days) {
      day.getSlots().size();
      day.getSnacks().size();
      out.add(intakeMapper.toDto(day));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public WeeklyAggregateDto getWeeklyAggregate(UUID userId, LocalDate weekStart) {
    if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new InvalidWeekStartException(weekStart == null ? null : weekStart.getDayOfWeek());
    }
    return intakeAggregator.aggregateWeek(userId, weekStart);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<IntakeAuditEntryDto> getIntakeAuditLog(
      UUID userId, LocalDate onDate, Pageable pageable) {
    Optional<IntakeDay> day = intakeDayRepository.findByUserIdAndOnDate(userId, onDate);
    if (day.isEmpty()) {
      return Page.empty(pageable);
    }
    return intakeAuditRepository
        .findByIntakeDay_IdOrderByOccurredAtDesc(day.get().getId(), pageable)
        .map(intakeMapper::toAuditEntryDto);
  }

  /**
   * C-B-048 intake-history search. Tenant scoping is enforced inside the JPQL ({@code
   * intakeDay.userId = :userId}); the {@code IntakeListFilter} components are short-circuited per
   * the {@code :param IS NULL OR ...} idiom in {@link IntakeDayRepository#searchSlots}.
   *
   * <p>The flat {@link IntakeSlotSearchResultDto} projection avoids serialising the full
   * planned/actual macro payload — search results are typically rendered as a row list, and the UI
   * can fetch a single slot's detail via the existing day endpoint when the user clicks through.
   */
  @Override
  @Transactional(readOnly = true)
  public Page<IntakeSlotSearchResultDto> searchIntakeSlots(
      UUID userId, IntakeListFilter filter, Pageable pageable) {
    IntakeListFilter safe = filter == null ? new IntakeListFilter(null, null, null) : filter;
    boolean hasQuery = safe.hasQuery();
    return intakeDayRepository
        .searchSlots(
            userId,
            safe.plannedRecipeId(),
            safe.mealSlot(),
            hasQuery ? safe.q() : "",
            hasQuery,
            pageable)
        .map(
            slot ->
                new IntakeSlotSearchResultDto(
                    slot.getId(),
                    slot.getIntakeDay().getId(),
                    slot.getIntakeDay().getOnDate(),
                    slot.getMealSlot(),
                    slot.getActualStatus(),
                    slot.getPlannedRecipeId(),
                    slot.getOverrideFreeText()));
  }

  // =================================================================================
  // Intake — write (01b)
  // =================================================================================

  @Override
  @Transactional
  public IntakeDayDto prefillFromPlan(
      UUID userId, LocalDate onDate, UUID planId, List<PlannedSlotInputDto> slots) {
    IntakeDay day =
        intakeDayRepository
            .findByUserIdAndOnDate(userId, onDate)
            .orElseGet(() -> newIntakeDay(userId, onDate, planId));
    day.setPlanId(planId);
    // Force lazy load before mutating (avoid unique-constraint flush race for already-prefilled).
    day.getSlots().size();

    // Replace slots wholesale: delete existing, insert new. Compute change-set from the desired
    // slots' meal-slot keys; we don't need a sophisticated diff because pre-fill is a system-only
    // path with planner-controlled inputs.
    Map<MealSlot, IntakeSlot> existing = new LinkedHashMap<>();
    for (IntakeSlot s : day.getSlots()) {
      existing.put(s.getMealSlot(), s);
    }
    // Wipe current slots; the planner snapshot is authoritative.
    day.getSlots().clear();
    if (slots != null) {
      for (PlannedSlotInputDto in : slots) {
        IntakeSlot slot =
            IntakeSlot.builder()
                .id(UUID.randomUUID())
                .mealSlot(in.mealSlot())
                .plannedRecipeId(in.plannedRecipeId())
                .plannedCalories(in.plannedCalories())
                .plannedProteinG(in.plannedProteinG())
                .plannedCarbsG(in.plannedCarbsG())
                .plannedFatG(in.plannedFatG())
                .plannedFibreG(in.plannedFibreG())
                .plannedMicros(in.plannedMicros())
                .actualStatus(IntakeSlotStatus.PENDING)
                .needsAiParse(false)
                .build();
        day.addSlot(slot);
      }
    }

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.PREFILL,
        null,
        null,
        objectMapper.valueToTree(Map.of("slotCount", existing.size())),
        objectMapper.valueToTree(Map.of("slotCount", day.getSlots().size(), "planId", planId)),
        now);
    publishIntakeEvent(userId, day, IntakeAuditAction.PREFILL, null, null, now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto confirmFromPlan(UUID userId, LocalDate onDate, MealSlot mealSlot) {
    IntakeDay day = requireDay(userId, onDate);
    IntakeSlot slot = requireSlot(day, userId, onDate, mealSlot);
    if (slot.getActualStatus() == IntakeSlotStatus.CONFIRMED) {
      // Idempotent — no audit row, no event.
      return intakeMapper.toDto(day);
    }
    IntakeSlotStatus prev = slot.getActualStatus();
    slot.setActualStatus(IntakeSlotStatus.CONFIRMED);
    slot.setActualCalories(slot.getPlannedCalories());
    slot.setActualProteinG(slot.getPlannedProteinG());
    slot.setActualCarbsG(slot.getPlannedCarbsG());
    slot.setActualFatG(slot.getPlannedFatG());
    slot.setActualFibreG(slot.getPlannedFibreG());
    slot.setActualMicros(slot.getPlannedMicros());
    slot.setNeedsAiParse(false);

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.CONFIRM,
        mealSlot,
        null,
        objectMapper.valueToTree(Map.of("status", prev.name())),
        objectMapper.valueToTree(Map.of("status", IntakeSlotStatus.CONFIRMED.name())),
        now);
    divergenceDetector.detectAndPublish(userId, onDate, UUID.randomUUID());
    publishIntakeEvent(userId, day, IntakeAuditAction.CONFIRM, mealSlot, null, now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto overrideIntakeFromFreeText(
      UUID userId, LocalDate onDate, MealSlot mealSlot, String freeText) {
    IntakeDay day = requireDay(userId, onDate);
    IntakeSlot slot = requireSlot(day, userId, onDate, mealSlot);

    IntakeSlotStatus prev = slot.getActualStatus();
    slot.setOverrideFreeText(freeText);
    slot.setOverriddenAt(Instant.now(clock));
    slot.setActualStatus(IntakeSlotStatus.OVERRIDDEN);
    slot.setNeedsAiParse(true); // 01k listener picks this up
    // Stub: zero actuals so aggregations stay sane until the AI parse lands.
    slot.setActualCalories(0);
    slot.setActualProteinG(BigDecimal.ZERO);
    slot.setActualCarbsG(BigDecimal.ZERO);
    slot.setActualFatG(BigDecimal.ZERO);
    slot.setActualFibreG(BigDecimal.ZERO);
    slot.setActualMicros(null);

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.OVERRIDE,
        mealSlot,
        null,
        objectMapper.valueToTree(Map.of("status", prev.name())),
        objectMapper.valueToTree(
            Map.of("status", IntakeSlotStatus.OVERRIDDEN.name(), "freeText", freeText)),
        now);
    divergenceDetector.detectAndPublish(userId, onDate, UUID.randomUUID());
    publishIntakeEvent(userId, day, IntakeAuditAction.OVERRIDE, mealSlot, null, now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto editIntakeManually(
      UUID userId, LocalDate onDate, MealSlot mealSlot, IntakeEntryDto entry) {
    IntakeDay day = requireDay(userId, onDate);
    IntakeSlot slot = requireSlot(day, userId, onDate, mealSlot);

    IntakeSlotStatus prev = slot.getActualStatus();
    slot.setActualStatus(IntakeSlotStatus.EDITED);
    slot.setActualCalories(entry.calories());
    slot.setActualProteinG(entry.proteinG());
    slot.setActualCarbsG(entry.carbsG());
    slot.setActualFatG(entry.fatG());
    slot.setActualFibreG(entry.fibreG());
    slot.setActualMicros(entry.micros());
    slot.setNeedsAiParse(false);
    slot.setOverrideFreeText(null);
    slot.setOverriddenAt(null);

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.EDIT,
        mealSlot,
        null,
        objectMapper.valueToTree(Map.of("status", prev.name())),
        objectMapper.valueToTree(
            Map.of("status", IntakeSlotStatus.EDITED.name(), "calories", entry.calories())),
        now);
    divergenceDetector.detectAndPublish(userId, onDate, UUID.randomUUID());
    publishIntakeEvent(userId, day, IntakeAuditAction.EDIT, mealSlot, null, now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto skipMeal(UUID userId, LocalDate onDate, MealSlot mealSlot) {
    IntakeDay day = requireDay(userId, onDate);
    IntakeSlot slot = requireSlot(day, userId, onDate, mealSlot);

    IntakeSlotStatus prev = slot.getActualStatus();
    slot.setActualStatus(IntakeSlotStatus.SKIPPED);
    slot.setActualCalories(0);
    slot.setActualProteinG(BigDecimal.ZERO);
    slot.setActualCarbsG(BigDecimal.ZERO);
    slot.setActualFatG(BigDecimal.ZERO);
    slot.setActualFibreG(BigDecimal.ZERO);
    slot.setActualMicros(null);
    slot.setNeedsAiParse(false);

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.SKIP,
        mealSlot,
        null,
        objectMapper.valueToTree(Map.of("status", prev.name())),
        objectMapper.valueToTree(Map.of("status", IntakeSlotStatus.SKIPPED.name())),
        now);
    divergenceDetector.detectAndPublish(userId, onDate, UUID.randomUUID());
    publishIntakeEvent(userId, day, IntakeAuditAction.SKIP, mealSlot, null, now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto logSnack(UUID userId, LocalDate onDate, LogSnackRequest request) {
    if (Boolean.TRUE.equals(request.deductFromPantry())) {
      // 01b no-op stub: the cross-module pantry-deduct lands in nutrition-01l.
      log.info(
          "logSnack deductFromPantry=true requested but no-op'd in 01b userId={} onDate={}",
          userId,
          onDate);
    }

    IntakeDay day = findOrCreateDay(userId, onDate);
    Instant now = Instant.now(clock);
    IntakeSnack snack =
        IntakeSnack.builder()
            .id(UUID.randomUUID())
            .freeText(request.freeText())
            .ingredientMappingKey(request.ingredientMappingKey())
            .quantityG(request.quantityG())
            .calories(request.calories())
            .proteinG(request.proteinG())
            .carbsG(request.carbsG())
            .fatG(request.fatG())
            .fibreG(request.fibreG())
            .micros(request.micros())
            .source(request.source())
            .loggedAt(now)
            .build();
    day.addSnack(snack);

    intakeDayRepository.saveAndFlush(day);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.SNACK_ADD,
        null,
        snack.getId(),
        objectMapper.nullNode(),
        objectMapper.valueToTree(
            Map.of(
                "freeText", request.freeText(),
                "calories", request.calories())),
        now);
    publishIntakeEvent(userId, day, IntakeAuditAction.SNACK_ADD, null, snack.getId(), now);
    return intakeMapper.toDto(day);
  }

  @Override
  @Transactional
  public IntakeDayDto removeSnack(UUID userId, LocalDate onDate, UUID snackId) {
    IntakeDay day =
        intakeDayRepository
            .findByUserIdAndOnDate(userId, onDate)
            .orElseThrow(() -> new IntakeSnackNotFoundException(userId, onDate, snackId));
    // Force load before searching.
    day.getSnacks().size();
    IntakeSnack target = null;
    for (IntakeSnack s : day.getSnacks()) {
      if (s.getId().equals(snackId)) {
        target = s;
        break;
      }
    }
    if (target == null) {
      throw new IntakeSnackNotFoundException(userId, onDate, snackId);
    }
    day.getSnacks().remove(target);

    intakeDayRepository.saveAndFlush(day);
    Instant now = Instant.now(clock);
    appendAudit(
        day,
        userId,
        IntakeAuditAction.SNACK_REMOVE,
        null,
        snackId,
        objectMapper.valueToTree(
            Map.of(
                "freeText", target.getFreeText(),
                "calories", target.getCalories())),
        objectMapper.nullNode(),
        now);
    publishIntakeEvent(userId, day, IntakeAuditAction.SNACK_REMOVE, null, snackId, now);
    return intakeMapper.toDto(day);
  }

  // =================================================================================
  // Daily activity (01b)
  // =================================================================================

  @Override
  @Transactional(readOnly = true)
  public Optional<DailyActivityDto> getDailyActivity(UUID userId, LocalDate onDate) {
    return dailyActivityLogRepository
        .findByUserIdAndOnDate(userId, onDate)
        .map(dailyActivityMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DailyActivityDto> getDailyActivityRange(UUID userId, LocalDate from, LocalDate to) {
    validateRange(from, to);
    List<DailyActivityLog> rows =
        dailyActivityLogRepository.findByUserIdAndOnDateBetween(userId, from, to);
    List<DailyActivityDto> out = new ArrayList<>(rows.size());
    for (DailyActivityLog row : rows) {
      out.add(dailyActivityMapper.toDto(row));
    }
    return out;
  }

  @Override
  @Transactional
  public DailyActivityDto upsertDailyActivity(
      UUID userId, LocalDate onDate, ActivityLevel level, String notes) {
    DailyActivityLog row =
        dailyActivityLogRepository
            .findByUserIdAndOnDate(userId, onDate)
            .orElseGet(
                () ->
                    DailyActivityLog.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .onDate(onDate)
                        .activityLevel(level)
                        .notes(notes)
                        .build());
    row.setActivityLevel(level);
    row.setNotes(notes);
    DailyActivityLog saved = dailyActivityLogRepository.saveAndFlush(row);
    return dailyActivityMapper.toDto(saved);
  }

  // =================================================================================
  // Food/mood journal (01c)
  // =================================================================================

  @Override
  @Transactional(readOnly = true)
  public List<FoodMoodEntryDto> getJournalEntriesForDay(UUID userId, LocalDate onDate) {
    return journalMapper.toDtos(
        journalRepository.findByUserIdAndOnDateOrderByLoggedAtAsc(userId, onDate));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<FoodMoodEntryDto> getRecentJournalEntries(UUID userId, Pageable pageable) {
    return journalRepository
        .findByUserIdOrderByLoggedAtDesc(userId, pageable)
        .map(journalMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<FoodMoodEntryDto> getJournalEntriesForFeedbackContext(UUID userId) {
    return journalMapper.toDtos(journalRepository.findTop20ByUserIdOrderByLoggedAtDesc(userId));
  }

  @Override
  @Transactional
  public FoodMoodEntryDto upsertJournalEntry(UUID userId, UpsertFoodMoodEntryRequest request) {
    // Create-only on POST per the LLD's REST table — slot-tied collisions on (userId, onDate,
    // mealSlot) fall through to the DB unique constraint and surface as 409 via the module
    // DataIntegrityViolationException handler.
    FoodMoodJournalEntry entry =
        FoodMoodJournalEntry.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(request.onDate())
            .mealSlot(request.mealSlot())
            .journalEntry(request.journalEntry())
            .loggedAt(request.loggedAt())
            .optimisticVersion(0L)
            .build();
    // saveAndFlush so the @CreationTimestamp / @Version-driven response carries the materialised
    // values (createdAt, optimisticVersion) — same gotcha as targets / intake / activity paths.
    FoodMoodJournalEntry saved = journalRepository.saveAndFlush(entry);
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new FoodMoodEntryWrittenEvent(
            saved.getId(),
            saved.getUserId(),
            saved.getOnDate(),
            saved.getMealSlot(),
            JournalAction.CREATED,
            UUID.randomUUID(),
            now));
    log.info(
        "journal entry created userId={} entryId={} onDate={} mealSlot={}",
        userId,
        saved.getId(),
        saved.getOnDate(),
        saved.getMealSlot());
    return journalMapper.toDto(saved);
  }

  @Override
  @Transactional
  public FoodMoodEntryDto updateJournalEntry(
      UUID userId, UUID entryId, UpsertFoodMoodEntryRequest request) {
    FoodMoodJournalEntry existing =
        journalRepository
            .findById(entryId)
            .filter(e -> e.getUserId().equals(userId))
            .orElseThrow(() -> new JournalEntryNotFoundException(entryId));
    if (!existing.getOnDate().equals(request.onDate())) {
      // Path-body / cross-day mismatches surface as 404 — entries cannot move across days via PUT
      // (the natural key includes onDate); to move, DELETE then POST.
      throw new JournalEntryNotFoundException(entryId);
    }
    if (existing.getOptimisticVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          FoodMoodJournalEntry.class, existing.getId());
    }

    existing.setMealSlot(request.mealSlot());
    existing.setJournalEntry(request.journalEntry());
    existing.setLoggedAt(request.loggedAt());

    // saveAndFlush so the @Version bump materialises before we map to the response DTO.
    FoodMoodJournalEntry saved = journalRepository.saveAndFlush(existing);
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new FoodMoodEntryWrittenEvent(
            saved.getId(),
            saved.getUserId(),
            saved.getOnDate(),
            saved.getMealSlot(),
            JournalAction.UPDATED,
            UUID.randomUUID(),
            now));
    log.info(
        "journal entry updated userId={} entryId={} version={}",
        userId,
        saved.getId(),
        saved.getOptimisticVersion());
    return journalMapper.toDto(saved);
  }

  @Override
  @Transactional
  public void deleteJournalEntry(UUID userId, UUID entryId) {
    FoodMoodJournalEntry existing =
        journalRepository
            .findById(entryId)
            .filter(e -> e.getUserId().equals(userId))
            .orElseThrow(() -> new JournalEntryNotFoundException(entryId));
    LocalDate onDate = existing.getOnDate();
    MealSlot mealSlot = existing.getMealSlot();
    journalRepository.delete(existing);
    Instant now = Instant.now(clock);
    eventPublisher.publishEvent(
        new FoodMoodEntryWrittenEvent(
            entryId, userId, onDate, mealSlot, JournalAction.DELETED, UUID.randomUUID(), now));
    log.info("journal entry deleted userId={} entryId={}", userId, entryId);
  }

  // =================================================================================
  // Intake helpers
  // =================================================================================

  private IntakeDay newIntakeDay(UUID userId, LocalDate onDate, UUID planId) {
    return IntakeDay.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .onDate(onDate)
        .planId(planId)
        .slots(new ArrayList<>())
        .snacks(new ArrayList<>())
        .auditLog(new ArrayList<>())
        .build();
  }

  private IntakeDay findOrCreateDay(UUID userId, LocalDate onDate) {
    return intakeDayRepository
        .findByUserIdAndOnDate(userId, onDate)
        .orElseGet(
            () -> {
              IntakeDay fresh = newIntakeDay(userId, onDate, null);
              IntakeDay persisted = intakeDayRepository.saveAndFlush(fresh);
              // Force lazy load so subsequent addSnack / addSlot mutate the managed collections.
              persisted.getSlots().size();
              persisted.getSnacks().size();
              return persisted;
            });
  }

  private IntakeDay requireDay(UUID userId, LocalDate onDate) {
    IntakeDay day =
        intakeDayRepository
            .findByUserIdAndOnDate(userId, onDate)
            .orElseThrow(() -> new IntakeDayNotFoundException(userId, onDate));
    day.getSlots().size();
    day.getSnacks().size();
    return day;
  }

  private static IntakeSlot requireSlot(
      IntakeDay day, UUID userId, LocalDate onDate, MealSlot mealSlot) {
    for (IntakeSlot s : day.getSlots()) {
      if (s.getMealSlot() == mealSlot) {
        return s;
      }
    }
    throw new IntakeSlotNotFoundException(userId, onDate, mealSlot);
  }

  private void appendAudit(
      IntakeDay day,
      UUID actorUserId,
      IntakeAuditAction action,
      MealSlot mealSlot,
      UUID snackId,
      JsonNode previousValue,
      JsonNode newValue,
      Instant occurredAt) {
    intakeAuditRepository.save(
        new IntakeAuditLog(
            UUID.randomUUID(),
            day,
            actorUserId,
            action,
            mealSlot,
            snackId,
            previousValue,
            newValue,
            occurredAt));
  }

  private void publishIntakeEvent(
      UUID userId,
      IntakeDay day,
      IntakeAuditAction action,
      MealSlot mealSlot,
      UUID snackId,
      Instant occurredAt) {
    eventPublisher.publishEvent(
        new IntakeLoggedEvent(
            userId,
            day.getId(),
            day.getOnDate(),
            action,
            mealSlot,
            snackId,
            UUID.randomUUID(),
            occurredAt));
  }

  private static void validateRange(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new InvalidIntakeRangeException("from and to are required");
    }
    if (from.isAfter(to)) {
      throw new InvalidIntakeRangeException("from must be on or before to");
    }
    long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1L;
    if (days > RANGE_MAX_DAYS) {
      throw new InvalidIntakeRangeException(
          "range must be at most " + RANGE_MAX_DAYS + " days (got " + days + ")");
    }
  }

  // =================================================================================
  // Ingredient mapping (01d)
  // =================================================================================

  @Override
  @Transactional(readOnly = true)
  public Optional<IngredientNutritionDto> lookupIngredient(String searchTerm) {
    String normalised = intakeKeyNormaliser.normalise(searchTerm);
    if (normalised == null || normalised.isEmpty()) {
      return Optional.empty();
    }
    return ingredientMappingRepository
        .findBySearchTerm(normalised)
        .map(ingredientMappingMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<IngredientNutritionDto> lookupIngredients(Collection<String> searchTerms) {
    if (searchTerms == null || searchTerms.isEmpty()) {
      return List.of();
    }
    List<String> normalised = new ArrayList<>(searchTerms.size());
    for (String t : searchTerms) {
      String n = intakeKeyNormaliser.normalise(t);
      if (n != null && !n.isEmpty()) {
        normalised.add(n);
      }
    }
    if (normalised.isEmpty()) {
      return List.of();
    }
    List<IngredientMapping> rows = ingredientMappingRepository.findBySearchTermIn(normalised);
    List<IngredientNutritionDto> out = new ArrayList<>(rows.size());
    for (IngredientMapping r : rows) {
      out.add(ingredientMappingMapper.toDto(r));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public IngredientLookupResultDto searchIngredientsForUi(IngredientLookupRequest request) {
    int max = request.maxResults() == null ? 10 : Math.min(Math.max(request.maxResults(), 1), 20);
    org.springframework.data.domain.Pageable pageable =
        org.springframework.data.domain.PageRequest.of(0, max);
    org.springframework.data.domain.Page<IngredientMapping> rows =
        ingredientMappingRepository.searchByTerm(request.query(), pageable);
    List<IngredientNutritionDto> hits = new ArrayList<>(rows.getNumberOfElements());
    for (IngredientMapping r : rows.getContent()) {
      hits.add(ingredientMappingMapper.toDto(r));
    }
    return new IngredientLookupResultDto(hits, true);
  }

  @Override
  @Transactional(readOnly = true)
  public org.springframework.data.domain.Page<IngredientNutritionDto> getMappingsNeedingReview(
      org.springframework.data.domain.Pageable pageable) {
    return ingredientMappingRepository
        .findByNeedsReviewTrueOrderByUpdatedAtDesc(pageable)
        .map(ingredientMappingMapper::toDto);
  }

  @Override
  @Transactional
  public IngredientNutritionDto correctIngredientMapping(
      String searchTerm,
      IngredientNutritionDocument override,
      long expectedVersion,
      UUID actorUserId) {
    String normalised = intakeKeyNormaliser.normalise(searchTerm);
    IngredientMapping row =
        ingredientMappingRepository
            .findBySearchTerm(normalised)
            .orElseThrow(() -> new IngredientMappingNotFoundException(normalised));
    if (row.getVersion() != expectedVersion) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          IngredientMapping.class, row.getId());
    }
    row.setNutritionPer100g(override);
    row.setSource(IngredientMappingSource.MANUAL);
    row.setConfidence(BigDecimal.valueOf(1.0));
    row.setNeedsReview(false);
    Instant now = Instant.now(clock);
    row.setLastVerifiedAt(now);

    // saveAndFlush so the @Version bump materialises before mapping to DTO.
    IngredientMapping saved = ingredientMappingRepository.saveAndFlush(row);
    eventPublisher.publishEvent(
        new IngredientMappingCorrectedEvent(
            saved.getId(), saved.getSearchTerm(), actorUserId, UUID.randomUUID(), now));
    log.info(
        "ingredient mapping corrected searchTerm={} actorUserId={} newVersion={}",
        saved.getSearchTerm(),
        actorUserId,
        saved.getVersion());
    return ingredientMappingMapper.toDto(saved);
  }

  // ---------------- 01e: health directives ----------------

  @Override
  @Transactional(readOnly = true)
  public Page<HealthDirectiveDto> getDirectives(
      UUID userId, DirectiveStatus filter, Pageable pageable) {
    Page<HealthDirective> rows =
        filter == null
            ? healthDirectiveRepository.findByUserIdOrderByReceivedAtDesc(userId, pageable)
            : healthDirectiveRepository.findByUserIdAndStatusOrderByReceivedAtDesc(
                userId, filter, pageable);
    return rows.map(healthDirectiveMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<HealthDirectiveDto> getDirective(UUID actorUserId, UUID directiveId) {
    return healthDirectiveRepository
        .findById(directiveId)
        .filter(d -> Objects.equals(d.getUserId(), actorUserId))
        .map(healthDirectiveMapper::toDto);
  }

  @Override
  @Transactional
  public HealthDirectiveDto receiveInboundDirective(
      UUID actorUserId, InboundHealthDirectiveRequest request) {
    // Idempotency check — re-delivery returns 409 with the existing row's id + status.
    Optional<HealthDirective> existing =
        healthDirectiveRepository.findBySourcePlatformAndExternalDirectiveId(
            request.sourcePlatform(), request.externalDirectiveId());
    if (existing.isPresent()) {
      HealthDirective row = existing.get();
      throw new DuplicateHealthDirectiveException(row.getId(), row.getStatus());
    }

    // Temporal-required-when-temporary check — IllegalArgumentException is mapped
    // to 400 by GlobalExceptionHandler. Avoids importing Spring Web types into
    // domain.service.internal (ArchUnit springWebStaysInApi rule).
    if (request.temporary() && request.autoExpiresAt() == null) {
      throw new IllegalArgumentException("autoExpiresAt is required when temporary=true");
    }

    Instant now = Instant.now(clock);
    HealthDirective directive =
        HealthDirective.builder()
            .id(UUID.randomUUID())
            .userId(request.userId())
            .externalDirectiveId(request.externalDirectiveId())
            .sourcePlatform(request.sourcePlatform())
            .receivedAt(now)
            .status(DirectiveStatus.PENDING_REVIEW)
            .directiveType(request.directiveType())
            .evidenceSummary(request.evidenceSummary())
            .evidenceConfidence(request.evidenceConfidence())
            .instructionPayload(request.instruction())
            .mapsToModel(request.mapsToModel())
            .mapsToTier(request.mapsToTier())
            .temporary(request.temporary())
            .autoExpiresAt(request.autoExpiresAt())
            .build();

    // saveAndFlush so @Version + timestamps materialise before mapping.
    HealthDirective saved = healthDirectiveRepository.saveAndFlush(directive);

    eventPublisher.publishEvent(
        new HealthDirectiveReceivedEvent(
            saved.getUserId(),
            saved.getId(),
            saved.getDirectiveType(),
            saved.getSourcePlatform(),
            saved.getReceivedAt(),
            UUID.randomUUID(),
            now));
    log.info(
        "health directive inbound persisted directiveId={} userId={} actorUserId={}",
        saved.getId(),
        saved.getUserId(),
        actorUserId);
    return healthDirectiveMapper.toDto(saved);
  }

  @Override
  @Transactional(noRollbackFor = HealthDirectiveSafetyGateBlockedException.class)
  public HealthDirectiveDto acceptHealthDirective(
      UUID actorUserId, UUID directiveId, AcceptDirectiveRequest request) {
    HealthDirective directive = loadOwnedDirective(actorUserId, directiveId);

    if (directive.getStatus() != DirectiveStatus.PENDING_REVIEW) {
      throw new HealthDirectiveAlreadyDecidedException(directiveId, directive.getStatus());
    }

    if (directive.getOptimisticVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          HealthDirective.class, directive.getId());
    }

    DirectiveInstructionDocument effective =
        request.userModification() != null
            ? request.userModification()
            : directive.getInstructionPayload();

    NutritionTargets currentTargets =
        targetsRepository.findByUserId(directive.getUserId()).orElse(null);

    SafetyGateResult gateResult =
        directiveSafetyGate.evaluate(effective, directive, currentTargets);

    // Persist gate verdict/findings regardless of outcome.
    directive.setSafetyGateVerdict(gateResult.verdict());
    directive.setSafetyGateFindings(gateResult.findings());

    if (gateResult.verdict() == SafetyGateVerdict.BLOCKED) {
      // Save the verdict/findings so the user can review them; status stays PENDING_REVIEW.
      healthDirectiveRepository.saveAndFlush(directive);
      throw new HealthDirectiveSafetyGateBlockedException(directiveId, gateResult.findings());
    }

    // Route via DirectiveApplier — joins this tx; preference_model with Noop throws 422.
    directiveApplier.apply(directive, effective, actorUserId);

    Instant now = Instant.now(clock);
    directive.setStatus(DirectiveStatus.ACCEPTED);
    directive.setDecidedAt(now);
    directive.setDecidedByUserId(actorUserId);
    directive.setUserModificationJson(request.userModification());

    HealthDirective saved = healthDirectiveRepository.saveAndFlush(directive);

    eventPublisher.publishEvent(
        new HealthDirectiveAcceptedEvent(
            saved.getUserId(),
            saved.getId(),
            saved.getDirectiveType(),
            saved.getMapsToModel(),
            saved.getMapsToTier(),
            request.userModification() != null,
            UUID.randomUUID(),
            now));
    log.info(
        "health directive accepted directiveId={} actorUserId={} mapsToModel={} verdict={}",
        saved.getId(),
        actorUserId,
        saved.getMapsToModel(),
        saved.getSafetyGateVerdict());
    return healthDirectiveMapper.toDto(saved);
  }

  @Override
  @Transactional
  public HealthDirectiveDto rejectHealthDirective(
      UUID actorUserId, UUID directiveId, RejectDirectiveRequest request) {
    HealthDirective directive = loadOwnedDirective(actorUserId, directiveId);

    if (directive.getStatus() != DirectiveStatus.PENDING_REVIEW) {
      throw new HealthDirectiveAlreadyDecidedException(directiveId, directive.getStatus());
    }

    if (directive.getOptimisticVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          HealthDirective.class, directive.getId());
    }

    directive.setStatus(DirectiveStatus.REJECTED);
    directive.setDecidedAt(Instant.now(clock));
    directive.setDecidedByUserId(actorUserId);
    directive.setRejectionReason(request.rejectionReason());

    HealthDirective saved = healthDirectiveRepository.saveAndFlush(directive);
    log.info(
        "health directive rejected directiveId={} actorUserId={} reason={}",
        saved.getId(),
        actorUserId,
        saved.getRejectionReason());
    return healthDirectiveMapper.toDto(saved);
  }

  /**
   * Resolve a directive id to its row, collapsing "not found" and "owned by another user" into a
   * single 404 so we don't leak existence.
   */
  private HealthDirective loadOwnedDirective(UUID actorUserId, UUID directiveId) {
    HealthDirective directive =
        healthDirectiveRepository
            .findById(directiveId)
            .orElseThrow(() -> new HealthDirectiveNotFoundException(directiveId));
    if (!Objects.equals(directive.getUserId(), actorUserId)) {
      throw new HealthDirectiveNotFoundException(directiveId);
    }
    return directive;
  }

  // ---------------- 01f: Recipe-version nutrition calculation ----------------

  private static final BigDecimal BD_100 = BigDecimal.valueOf(100L);

  @Override
  @Transactional(readOnly = true)
  public RecipeNutritionResultDto calculateRecipeNutrition(
      CalculateRecipeNutritionRequest request) {
    return computeRecipeNutrition(request, "save-time");
  }

  @Override
  @Transactional(readOnly = true)
  public RecipeNutritionResultDto recalculateForEvolvedRecipe(
      CalculateRecipeNutritionRequest request) {
    return computeRecipeNutrition(request, "recalc");
  }

  /**
   * Shared helper. Reads the {@code IngredientMapping} cache via {@link
   * IngredientMappingRepository#findBySearchTermIn} (batch, amortises round-trips), multiplies each
   * line's {@code gramsEstimate / 100} by the mapping's per-100g nutrition, sums, then divides by
   * the request's {@code servings}. Status is one of {@code calculated} / {@code partial} / {@code
   * pending} per LLD §nutritionStatus.
   */
  private RecipeNutritionResultDto computeRecipeNutrition(
      CalculateRecipeNutritionRequest request, String phase) {
    List<RecipeIngredientLineDto> lines = request.ingredients();

    // First pass: normalise the lookup key per line; collect distinct keys for the batch query.
    String[] keys = new String[lines.size()];
    LinkedHashSet<String> distinctKeys = new LinkedHashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      RecipeIngredientLineDto line = lines.get(i);
      String key = line.ingredientMappingKey();
      if (key == null || key.isBlank()) {
        if (line.name() != null && !line.name().isBlank()) {
          key = intakeKeyNormaliser.normalise(line.name());
        } else {
          key = null;
        }
      }
      keys[i] = key;
      if (key != null) {
        distinctKeys.add(key);
      }
    }

    Map<String, IngredientMapping> byKey = new HashMap<>();
    if (!distinctKeys.isEmpty()) {
      for (IngredientMapping m : ingredientMappingRepository.findBySearchTermIn(distinctKeys)) {
        byKey.put(m.getSearchTerm(), m);
      }
    }

    BigDecimal totalCalories = BigDecimal.ZERO;
    BigDecimal totalProtein = BigDecimal.ZERO;
    BigDecimal totalCarbs = BigDecimal.ZERO;
    BigDecimal totalFat = BigDecimal.ZERO;
    BigDecimal totalFibre = BigDecimal.ZERO;
    Map<String, BigDecimal> totalMicros = new LinkedHashMap<>();
    List<UnmappedIngredientDto> unmapped = new ArrayList<>();
    boolean anyNeedsReview = false;
    int resolvedCount = 0;

    for (int i = 0; i < lines.size(); i++) {
      RecipeIngredientLineDto line = lines.get(i);
      String key = keys[i];
      IngredientMapping mapping = key == null ? null : byKey.get(key);
      if (mapping == null) {
        unmapped.add(new UnmappedIngredientDto(line.name(), "not-in-cache", BigDecimal.ZERO));
        continue;
      }
      resolvedCount++;
      if (mapping.isNeedsReview()) {
        anyNeedsReview = true;
      }
      BigDecimal grams = line.gramsEstimate() != null ? line.gramsEstimate() : BigDecimal.ZERO;
      // factor = grams/100 — keep 6 d.p. so per-line rounding does not bias the sum.
      BigDecimal factor = grams.divide(BD_100, 6, RoundingMode.HALF_UP);
      IngredientNutritionDocument doc = mapping.getNutritionPer100g();
      if (doc == null) {
        // The cache row has no nutrition payload — treat as unmapped.
        unmapped.add(new UnmappedIngredientDto(line.name(), "no-nutrition-doc", BigDecimal.ZERO));
        resolvedCount--;
        continue;
      }
      if (doc.calories() != null) {
        totalCalories =
            totalCalories.add(BigDecimal.valueOf(doc.calories().longValue()).multiply(factor));
      }
      if (doc.proteinG() != null) {
        totalProtein = totalProtein.add(doc.proteinG().multiply(factor));
      }
      if (doc.carbsG() != null) {
        totalCarbs = totalCarbs.add(doc.carbsG().multiply(factor));
      }
      if (doc.fatG() != null) {
        totalFat = totalFat.add(doc.fatG().multiply(factor));
      }
      if (doc.fibreG() != null) {
        totalFibre = totalFibre.add(doc.fibreG().multiply(factor));
      }
      if (doc.micros() != null) {
        final BigDecimal lineFactor = factor;
        doc.micros()
            .forEach(
                (k, v) -> {
                  if (v != null) {
                    totalMicros.merge(k, v.multiply(lineFactor), BigDecimal::add);
                  }
                });
      }
    }

    BigDecimal servings = BigDecimal.valueOf(request.servings().longValue());
    int caloriesPerServing =
        totalCalories.divide(servings, 0, RoundingMode.HALF_UP).intValueExact();

    Map<String, BigDecimal> microsPerServing = new LinkedHashMap<>();
    totalMicros.forEach(
        (k, v) -> microsPerServing.put(k, v.divide(servings, 2, RoundingMode.HALF_UP)));

    String status;
    if (resolvedCount == 0) {
      status = "pending";
    } else if (unmapped.isEmpty() && !anyNeedsReview) {
      status = "calculated";
    } else {
      status = "partial";
    }

    RecipeNutritionResultDto result =
        new RecipeNutritionResultDto(
            request.recipeId(),
            caloriesPerServing,
            totalProtein.divide(servings, 2, RoundingMode.HALF_UP),
            totalCarbs.divide(servings, 2, RoundingMode.HALF_UP),
            totalFat.divide(servings, 2, RoundingMode.HALF_UP),
            totalFibre.divide(servings, 2, RoundingMode.HALF_UP),
            microsPerServing,
            status,
            List.copyOf(unmapped));

    log.debug(
        "nutrition calc phase={} recipeId={} servings={} resolved={} unmapped={} status={}",
        phase,
        request.recipeId(),
        request.servings(),
        resolvedCount,
        unmapped.size(),
        status);

    return result;
  }

  // ---------------- 01g: Floor-gate evaluation ----------------

  @Override
  @Transactional(readOnly = true)
  public FloorGateResultDto evaluate(UUID userId, CandidatePlanRollupDto rollup) {
    validateRollupShape(rollup);

    Optional<NutritionTargets> targetsOpt = targetsRepository.findByUserId(userId);
    if (targetsOpt.isEmpty()) {
      return new FloorGateResultDto(
          true, List.of(), "No targets configured — gate passes by default");
    }
    NutritionTargets targets = targetsOpt.get();
    // Force lazy-load of the micros child list inside the read-only tx (per 01a's convention; the
    // aggregate has three list children so no multi-attribute @EntityGraph is possible).
    targets.getMicroTargets().size();

    List<MacroFloor> macroFloors = collectMacroFloors(targets);
    List<MicroFloor> microFloors = collectMicroFloors(targets);

    List<FloorViolationDto> violations = new ArrayList<>();
    for (CandidateDailyRollupDto day : rollup.perDay()) {
      for (MacroFloor mf : macroFloors) {
        BigDecimal actual = mf.extract(day);
        if (actual == null) {
          actual = BigDecimal.ZERO;
        }
        if (actual.compareTo(mf.floor()) < 0) {
          violations.add(new FloorViolationDto(day.date(), mf.key(), mf.floor(), actual));
        }
      }
      for (MicroFloor mfi : microFloors) {
        BigDecimal actual =
            day.micros() == null
                ? BigDecimal.ZERO
                : day.micros().getOrDefault(mfi.key(), BigDecimal.ZERO);
        if (actual.compareTo(mfi.floor()) < 0) {
          violations.add(new FloorViolationDto(day.date(), mfi.key(), mfi.floor(), actual));
        }
      }
    }

    boolean passed = violations.isEmpty();
    String summary;
    if (passed) {
      summary = "Plan passes all hard floors across " + rollup.perDay().size() + " day(s)";
    } else {
      long distinctDates = violations.stream().map(FloorViolationDto::date).distinct().count();
      summary =
          "Plan fails " + violations.size() + " hard floor(s) across " + distinctDates + " day(s)";
    }
    return new FloorGateResultDto(passed, List.copyOf(violations), summary);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, FloorGateResultDto> evaluateForHousehold(
      List<UUID> userIds, CandidatePlanRollupDto rollup) {
    LinkedHashMap<UUID, FloorGateResultDto> out = new LinkedHashMap<>();
    if (userIds == null || userIds.isEmpty()) {
      return out;
    }
    for (UUID userId : userIds) {
      out.put(userId, evaluate(userId, rollup));
    }
    return out;
  }

  /**
   * Service-layer rollup shape validation. The controller catches and bubbles {@link
   * InvalidPlanRollupException} as 400; calling {@code evaluate} directly (in-process) also
   * triggers the same check so the planner cannot pass a malformed envelope.
   */
  private static void validateRollupShape(CandidatePlanRollupDto rollup) {
    if (rollup.endDate().isBefore(rollup.startDate())) {
      throw new InvalidPlanRollupException(
          "endDate ("
              + rollup.endDate()
              + ") must not precede startDate ("
              + rollup.startDate()
              + ")");
    }
    for (CandidateDailyRollupDto day : rollup.perDay()) {
      LocalDate d = day.date();
      if (d.isBefore(rollup.startDate()) || d.isAfter(rollup.endDate())) {
        throw new InvalidPlanRollupException(
            "perDay date "
                + d
                + " is outside ["
                + rollup.startDate()
                + ", "
                + rollup.endDate()
                + "]");
      }
    }
  }

  /**
   * Collect the macro hard-floors. LLD says macros default to {@code isHardFloor=true}; the
   * persisted schema does not carry an {@code is_hard_floor} column on the macro fields, so 01g
   * treats any macro whose {@code <macro>FloorG} column is non-null as a hard floor (the column is
   * the only way the floor is expressed at all). A {@code null} floor implies "no floor configured"
   * regardless of the LLD default — there is nothing to compare against.
   */
  private static List<MacroFloor> collectMacroFloors(NutritionTargets t) {
    List<MacroFloor> out = new ArrayList<>(4);
    if (t.getProteinFloorG() != null) {
      out.add(new MacroFloor("protein", t.getProteinFloorG(), CandidateDailyRollupDto::proteinG));
    }
    if (t.getCarbsFloorG() != null) {
      out.add(new MacroFloor("carbs", t.getCarbsFloorG(), CandidateDailyRollupDto::carbsG));
    }
    if (t.getFatFloorG() != null) {
      out.add(new MacroFloor("fat", t.getFatFloorG(), CandidateDailyRollupDto::fatG));
    }
    if (t.getFibreFloorG() != null) {
      out.add(new MacroFloor("fibre", t.getFibreFloorG(), CandidateDailyRollupDto::fibreG));
    }
    return out;
  }

  /**
   * Collect the micro hard-floors. LLD says micros default to {@code isHardFloor=false}; the
   * persisted schema does not carry an {@code is_hard_floor} column on {@code MicroTarget} either.
   * To honour the LLD default verbatim, 01g treats no micro as hard-floored at this revision (the
   * gate never raises a micro violation). When 01h adds the column, this method becomes the only
   * place to update.
   */
  private static List<MicroFloor> collectMicroFloors(NutritionTargets t) {
    // No is_hard_floor column on MicroTarget yet — default isHardFloor=false per LLD line 774, so
    // no micro contributes to the hard-floor list. List<MicroFloor> stays empty by design.
    return List.of();
  }

  /** Internal record carrying a macro key + its floor + the rollup field extractor. */
  private record MacroFloor(
      String key,
      BigDecimal floor,
      java.util.function.Function<CandidateDailyRollupDto, BigDecimal> extract) {
    BigDecimal extract(CandidateDailyRollupDto day) {
      return extract.apply(day);
    }
  }

  /** Internal record carrying a micro key + its floor. */
  private record MicroFloor(String key, BigDecimal floor) {}
}
