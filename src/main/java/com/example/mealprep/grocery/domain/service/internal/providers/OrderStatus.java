package com.example.mealprep.grocery.domain.service.internal.providers;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import java.time.Instant;
import java.util.List;

/**
 * Result of {@link GroceryProvider#checkStatus}. Per lld/grocery.md line 665. {@code
 * normalisedStatus} maps the provider-native status onto the module's lifecycle enum; {@code
 * substitutions} carries any provider-proposed substitutions surfaced at this poll.
 */
public record OrderStatus(
    GroceryOrderStatus normalisedStatus,
    String providerNativeStatus,
    Instant deliverySlotStart,
    Instant deliverySlotEnd,
    Integer confirmedTotalPence,
    Integer paidTotalPence,
    List<SubstitutionProposal> substitutions,
    Instant statusObservedAt) {}
