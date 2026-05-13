package com.example.mealprep.discovery.domain.entity;

/** Result of the robots.txt politeness gate for a single candidate URL. Per LLD line 197. */
public enum RobotsTxtOutcome {
  ALLOWED,
  DISALLOWED,
  UNAVAILABLE,
  SKIPPED
}
