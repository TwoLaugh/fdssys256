package com.example.mealprep.grocery.domain.service.internal.providers;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * A provider-proposed substitution surfaced via {@link OrderStatus}. Per lld/grocery.md line 666.
 * {@code rawPayload} carries the opaque provider blob (populated for the {@code UNPARSED} case so
 * the user can resolve manually). The persistence of these proposals is grocery-01f's {@code
 * SubstitutionPersister}; 01e stubs it inline in {@code markDelivered}.
 */
public record SubstitutionProposal(
    String originalProductId,
    String originalDisplayName,
    String substituteProductId,
    String substituteDisplayName,
    BigDecimal substituteQuantity,
    String substituteUnit,
    Integer substituteUnitPence,
    String reason,
    JsonNode rawPayload) {}
