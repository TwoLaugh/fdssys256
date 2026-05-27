package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read shape for a Tier-3 substitution proposal. Per lld/grocery.md lines 432-439. */
public record GrocerySubstitutionProposalDto(
    UUID id,
    UUID groceryOrderId,
    UUID groceryOrderLineId,
    String originalProductId,
    String originalDisplayName,
    String originalIngredientMappingKey,
    String substituteProductId,
    String substituteDisplayName,
    String substituteIngredientMappingKey,
    BigDecimal substituteQuantity,
    String substituteUnit,
    Integer substituteUnitPence,
    String reason,
    SubstitutionProposalStatus proposalStatus,
    Instant resolvedAt,
    UUID resolvedByUserId) {}
