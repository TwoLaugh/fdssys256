package com.example.mealprep.grocery.api.dto;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read shape for a Tier-3 grocery order. Per lld/grocery.md lines 411-422. */
public record GroceryOrderDto(
    UUID id,
    UUID userId,
    UUID householdId,
    UUID shoppingListId,
    String providerKey,
    String providerOrderId,
    GroceryOrderStatus status,
    String statusReason,
    Integer quotedTotalPence,
    Integer confirmedTotalPence,
    Integer paidTotalPence,
    String currency,
    Instant deliverySlotStart,
    Instant deliverySlotEnd,
    String confirmLink,
    Instant placedAt,
    Instant confirmedAt,
    Instant deliveredAt,
    Instant reconciledAt,
    Instant cancelledAt,
    String cancelReason,
    Instant lastStatusCheckAt,
    List<GroceryOrderLineDto> lines,
    List<GrocerySubstitutionProposalDto> outstandingProposals,
    long version) {}
