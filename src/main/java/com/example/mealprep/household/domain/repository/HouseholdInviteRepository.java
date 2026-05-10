package com.example.mealprep.household.domain.repository;

import com.example.mealprep.household.domain.entity.HouseholdInvite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HouseholdInvite}. Cross-module callers go through {@code
 * HouseholdQueryService} / {@code HouseholdUpdateService} — enforced by {@code
 * HouseholdBoundaryTest} (ArchUnit). Package-private — only the in-module {@code
 * domain.service.internal} package needs it.
 */
public interface HouseholdInviteRepository extends JpaRepository<HouseholdInvite, UUID> {

  /**
   * Lookup-by-code for the accept flow. The DB has a partial index on this column restricted to
   * {@code accepted_at IS NULL AND revoked_at IS NULL}; non-pending rows still match the unique
   * constraint, but the partial index is the planner-preferred path for the hot lookup.
   */
  Optional<HouseholdInvite> findByInviteCode(String inviteCode);

  /** Pending invites for a household, newest-first. Hits the partial household-scoped index. */
  List<HouseholdInvite> findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(
      UUID householdId);
}
