package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.ActivityAdjustmentDto;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.ActivityAdjustment;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
public class NutritionServiceImpl implements NutritionQueryService, NutritionUpdateService {

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

  private final NutritionTargetsRepository targetsRepository;
  private final NutritionTargetsAuditRepository auditRepository;
  private final TargetsMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public NutritionServiceImpl(
      NutritionTargetsRepository targetsRepository,
      NutritionTargetsAuditRepository auditRepository,
      TargetsMapper mapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.targetsRepository = targetsRepository;
    this.auditRepository = auditRepository;
    this.mapper = mapper;
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
}
