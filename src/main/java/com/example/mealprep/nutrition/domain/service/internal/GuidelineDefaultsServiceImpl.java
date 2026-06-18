package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.ComputeTargetsRequest;
import com.example.mealprep.nutrition.api.dto.ComputedTargetDefaultsDto;
import com.example.mealprep.nutrition.domain.entity.BiologicalSex;
import com.example.mealprep.nutrition.domain.entity.DriDefault;
import com.example.mealprep.nutrition.domain.repository.DriDefaultRepository;
import com.example.mealprep.nutrition.domain.service.GuidelineDefaultsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Assembles a full set of guideline-default targets from demographics: macros + calories from {@link
 * TargetGuidelineCalculator}, and micronutrient floors looked up from the {@code (age_group, sex)}
 * DRI band (the weight-free, age/sex-driven part). Stateless — returns a preview; persistence happens
 * when the user saves the (possibly edited) targets through the normal PUT.
 */
@Service
public class GuidelineDefaultsServiceImpl implements GuidelineDefaultsService {

  // Default saturated-fat ceiling: 10% of calories (the "Moderate" fat-spread preset).
  private static final BigDecimal SAT_FAT_FRACTION_OF_KCAL = new BigDecimal("0.10");
  private static final int KCAL_PER_G_FAT = 9;

  // The body-size-dependent micros: thiamin/riboflavin/niacin are energy-metabolism coenzymes whose
  // requirement tracks CALORIE throughput; B6 tracks PROTEIN intake. We raise these above the flat
  // age/sex DRI when the person's (weight-driven) calorie/protein targets imply more — never below
  // the DRI bracket, which keeps its population safety margin. The rest of the micros stay flat
  // (their safety margin already covers body-size variation).
  private static final double THIAMIN_MG_PER_1000KCAL = 0.5;
  private static final double RIBOFLAVIN_MG_PER_1000KCAL = 0.6;
  private static final double NIACIN_MG_PER_1000KCAL = 6.6;
  private static final double B6_MG_PER_G_PROTEIN = 0.016;

  private final TargetGuidelineCalculator calculator;
  private final DriDefaultRepository driDefaultRepository;
  private final Clock clock;

  public GuidelineDefaultsServiceImpl(
      TargetGuidelineCalculator calculator,
      DriDefaultRepository driDefaultRepository,
      Clock clock) {
    this.calculator = calculator;
    this.driDefaultRepository = driDefaultRepository;
    this.clock = clock;
  }

  @Override
  public ComputedTargetDefaultsDto compute(ComputeTargetsRequest req) {
    LocalDate today = LocalDate.now(clock);
    int age = calculator.ageYears(req.dateOfBirth(), today);
    String ageGroup = calculator.ageGroup(req.dateOfBirth(), today);

    int bmr = calculator.bmr(req.biologicalSex(), age, req.weightKg(), req.heightCm());
    int calories =
        calculator.targetCalories(
            req.biologicalSex(), age, req.weightKg(), req.heightCm(), req.activityLevel(), req.goal());
    BigDecimal protein = calculator.proteinFloorG(req.weightKg());
    BigDecimal fat = calculator.fatG(calories);
    BigDecimal fibre = calculator.fibreG(calories);
    BigDecimal carbs = calculator.carbsG(calories, protein, fat);
    BigDecimal satFat =
        SAT_FAT_FRACTION_OF_KCAL
            .multiply(BigDecimal.valueOf(calories))
            .divide(BigDecimal.valueOf(KCAL_PER_G_FAT), 1, RoundingMode.HALF_UP);

    // Micronutrient floors are an age/sex/life-stage DRI LOOKUP (not weight-scaled). Seed column
    // 'sex' is lower-case ('male'/'female'); map the enum to it. Pregnancy/lactation floors only
    // exist for a reproductive-age female — if the band has none, fall back to the NONE rows.
    String sex = req.biologicalSex() == BiologicalSex.MALE ? "male" : "female";
    String lifeStage = req.lifeStage() == null ? "NONE" : req.lifeStage().name();
    List<DriDefault> driRows =
        driDefaultRepository.findByAgeGroupAndSexAndLifeStage(ageGroup, sex, lifeStage);
    if (driRows.isEmpty() && !"NONE".equals(lifeStage)) {
      driRows = driDefaultRepository.findByAgeGroupAndSexAndLifeStage(ageGroup, sex, "NONE");
    }
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    for (DriDefault dri : driRows) {
      micros.put(dri.getMicroName(), dri.getRdaValue());
    }
    // Scale the energy/protein-linked micros above the flat DRI when body size implies more.
    raiseFloor(micros, "thiamin_mg", THIAMIN_MG_PER_1000KCAL * calories / 1000.0);
    raiseFloor(micros, "riboflavin_mg", RIBOFLAVIN_MG_PER_1000KCAL * calories / 1000.0);
    raiseFloor(micros, "niacin_mg", NIACIN_MG_PER_1000KCAL * calories / 1000.0);
    raiseFloor(micros, "vitamin_b6_mg", B6_MG_PER_G_PROTEIN * protein.doubleValue());

    return new ComputedTargetDefaultsDto(
        calories, bmr, ageGroup, protein, carbs, fat, fibre, satFat, micros);
  }

  /**
   * Raise an existing micro floor to {@code computed} when that is higher than the DRI value — never
   * below it (so the DRI safety margin is preserved). No-op if the key isn't in the DRI set.
   */
  private static void raiseFloor(Map<String, BigDecimal> micros, String key, double computed) {
    micros.computeIfPresent(
        key,
        (k, dri) -> {
          BigDecimal c = BigDecimal.valueOf(computed).setScale(2, RoundingMode.HALF_UP);
          return c.compareTo(dri) > 0 ? c : dri;
        });
  }
}
