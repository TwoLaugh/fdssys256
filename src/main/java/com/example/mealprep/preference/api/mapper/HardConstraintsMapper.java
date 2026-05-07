package com.example.mealprep.preference.api.mapper;

import com.example.mealprep.preference.api.dto.AgeRestrictionDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping. Dietary identity is split across three fields on the entity ({@code
 * dietaryIdentityBase}, {@code dietaryIdentityLabel}, {@code exceptions}) but unified into a single
 * {@link DietaryIdentityDto} on the API surface — the mapping is hand-written rather than relying
 * on MapStruct field-matching.
 */
@Mapper(componentModel = "spring")
public interface HardConstraintsMapper {

  default HardConstraintsDto toDto(HardConstraints entity) {
    if (entity == null) {
      return null;
    }
    return new HardConstraintsDto(
        entity.getId(),
        entity.getUserId(),
        defensiveCopy(entity.getAllergies()),
        new DietaryIdentityDto(
            entity.getDietaryIdentityBase(),
            entity.getDietaryIdentityLabel(),
            mapExceptions(entity.getExceptions())),
        defensiveCopy(entity.getMedicalDiets()),
        mapIntolerances(entity.getIntolerances()),
        mapAgeRestrictions(entity.getAgeRestrictions()),
        entity.getVersion());
  }

  default HardConstraintsAuditEntryDto toAuditEntryDto(HardConstraintsAuditLog entity) {
    if (entity == null) {
      return null;
    }
    return new HardConstraintsAuditEntryDto(
        entity.getId(),
        entity.getHardConstraintsId(),
        entity.getActorUserId(),
        entity.getFieldChanged(),
        entity.getPreviousValueJson(),
        entity.getNewValueJson(),
        entity.getOccurredAt());
  }

  private static List<DietaryIdentityExceptionDto> mapExceptions(
      List<DietaryIdentityException> exceptions) {
    if (exceptions == null || exceptions.isEmpty()) {
      return Collections.emptyList();
    }
    List<DietaryIdentityExceptionDto> result = new ArrayList<>(exceptions.size());
    for (DietaryIdentityException e : exceptions) {
      result.add(new DietaryIdentityExceptionDto(e.getAllows(), e.getFrequency(), e.getContext()));
    }
    return result;
  }

  private static List<HardIntoleranceDto> mapIntolerances(List<HardIntolerance> intolerances) {
    if (intolerances == null || intolerances.isEmpty()) {
      return Collections.emptyList();
    }
    List<HardIntoleranceDto> result = new ArrayList<>(intolerances.size());
    for (HardIntolerance i : intolerances) {
      result.add(new HardIntoleranceDto(i.getSubstance(), i.getSeverity(), i.getNotes()));
    }
    return result;
  }

  private static List<AgeRestrictionDto> mapAgeRestrictions(List<AgeRestriction> ageRestrictions) {
    if (ageRestrictions == null || ageRestrictions.isEmpty()) {
      return Collections.emptyList();
    }
    List<AgeRestrictionDto> result = new ArrayList<>(ageRestrictions.size());
    for (AgeRestriction r : ageRestrictions) {
      result.add(new AgeRestrictionDto(r.getRuleKey(), r.isAutoPopulated()));
    }
    return result;
  }

  private static List<String> defensiveCopy(List<String> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(source);
  }
}
