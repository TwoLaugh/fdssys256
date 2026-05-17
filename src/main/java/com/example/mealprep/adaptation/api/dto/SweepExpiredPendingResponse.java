package com.example.mealprep.adaptation.api.dto;

/**
 * Response for {@code POST /admin/sweep-expired-pending}: the number of PENDING rows flipped to
 * EXPIRED on this invocation.
 *
 * <p>Per ticket 01f §AdaptationAdminController.
 */
public record SweepExpiredPendingResponse(int touched) {}
