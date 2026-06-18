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

    // Micronutrient floors are an age/sex DRI LOOKUP (not weight-scaled). Seed column 'sex' is
    // lower-case ('male'/'female'); map the enum to it.
    String sex = req.biologicalSex() == BiologicalSex.MALE ? "male" : "female";
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    for (DriDefault dri : driDefaultRepository.findByAgeGroupAndSex(ageGroup, sex)) {
      micros.put(dri.getMicroName(), dri.getRdaValue());
    }

    return new ComputedTargetDefaultsDto(
        calories, bmr, ageGroup, protein, carbs, fat, fibre, satFat, micros);
  }
}
