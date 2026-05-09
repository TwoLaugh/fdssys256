package com.example.mealprep.provisions.domain.entity;

/** Where an inventory row came from. Drives idempotency keys for grocery imports (01h). */
public enum ItemSource {
  TESCO_ORDER,
  OTHER_SHOP,
  MANUAL_ADD,
  BATCH_COOK,
  GIFT
}
