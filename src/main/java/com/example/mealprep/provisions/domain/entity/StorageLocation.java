package com.example.mealprep.provisions.domain.entity;

/** Where the item lives. {@code SPICE_RACK} is status-tracked; the others are quantity-tracked. */
public enum StorageLocation {
  FRIDGE,
  FREEZER,
  CUPBOARD,
  SPICE_RACK
}
