package com.example.mealprep.household.domain.entity;

/**
 * Role of a {@link HouseholdMember} within their {@link Household}. Constants are declared in
 * lower-case so JPA's default {@code @Enumerated(STRING)} mapping persists them as the literal
 * strings {@code 'primary'} / {@code 'member'} — the partial unique index {@code
 * idx_household_member_one_primary} predicates on {@code role = 'primary'}, and we want JPA's
 * stored value to match the predicate without a converter.
 */
public enum HouseholdRole {
  primary,
  member
}
