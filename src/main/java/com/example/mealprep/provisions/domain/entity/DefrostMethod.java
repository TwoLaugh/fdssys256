package com.example.mealprep.provisions.domain.entity;

/** How a freezer item is defrosted; drives lead-time scheduling in the planner (01g+). */
public enum DefrostMethod {
  OVERNIGHT_FRIDGE,
  ROOM_TEMP,
  MICROWAVE,
  QUICK_DEFROST
}
