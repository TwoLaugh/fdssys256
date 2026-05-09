package com.example.mealprep.household.domain.service;

import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import java.util.UUID;

/**
 * Write API for the household module. 01a only ships {@link #createHousehold} — settings PUT,
 * member CRUD, role-change, invite accept, and merge land in subsequent sub-tickets.
 */
public interface HouseholdUpdateService {

  /**
   * Create a new household and seat the creator as its first {@code primary} member in a single
   * transaction. Throws {@code UserAlreadyInHouseholdException} if the creator is already a member
   * of any household.
   */
  HouseholdDto createHousehold(UUID creatorUserId, CreateHouseholdRequest request);
}
