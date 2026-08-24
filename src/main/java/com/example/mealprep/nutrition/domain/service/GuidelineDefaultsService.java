package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.ComputeTargetsRequest;
import com.example.mealprep.nutrition.api.dto.ComputedTargetDefaultsDto;

/**
 * Computes guideline-default nutrition targets from a person's demographics + goal (BMR calories,
 * protein g/kg, the age/sex DRI micronutrient floors). Stateless preview — the user reviews and
 * edits the result before saving via the normal targets PUT. Public service interface; impl lives
 * in {@code domain.service.internal}.
 */
public interface GuidelineDefaultsService {

  ComputedTargetDefaultsDto compute(ComputeTargetsRequest request);
}
