package com.example.mealprep.provisions.domain.entity;

/**
 * How an inventory row's stock level is tracked. {@code QUANTITY} carries a numeric quantity +
 * unit; {@code STATUS} carries a {@link StapleStatus} ({@code STOCKED}/{@code LOW}/{@code OUT}).
 */
public enum TrackingMode {
  QUANTITY,
  STATUS
}
