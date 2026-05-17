package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.domain.entity.RoutingFailureKind;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Return type from a {@link DestinationDispatcher}. Per ticket 01d §3.
 *
 * <p>On {@code APPLIED} or {@code AWAITING_USER_APPROVAL} — {@code actionTaken} + {@code
 * destinationResultJson} are populated; {@code failureKind} / {@code failureMessage} are null. On
 * {@code FAILED} — {@code failureKind} + {@code failureMessage} are populated; {@code actionTaken}
 * + {@code destinationResultJson} are null.
 */
public record DispatchResult(
    RoutingStatus status,
    String actionTaken,
    JsonNode destinationResultJson,
    RoutingFailureKind failureKind,
    String failureMessage) {

  public static DispatchResult applied(String actionTaken, JsonNode result) {
    return new DispatchResult(RoutingStatus.APPLIED, actionTaken, result, null, null);
  }

  public static DispatchResult awaitingApproval(String actionTaken, JsonNode result) {
    return new DispatchResult(
        RoutingStatus.AWAITING_USER_APPROVAL, actionTaken, result, null, null);
  }

  public static DispatchResult failed(RoutingFailureKind kind, String msg) {
    return new DispatchResult(RoutingStatus.FAILED, null, null, kind, msg);
  }
}
