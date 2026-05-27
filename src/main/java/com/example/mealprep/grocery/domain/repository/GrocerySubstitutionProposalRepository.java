package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GrocerySubstitutionProposal}. Package-private. Standard-shape
 * per lld/grocery.md line 542. {@code countByGroceryOrderIdAndProposalStatus} gates reconciliation
 * — an order cannot move to {@code RECONCILED} while any proposal is {@code PENDING_USER_REVIEW}.
 */
interface GrocerySubstitutionProposalRepository
    extends JpaRepository<GrocerySubstitutionProposal, UUID> {

  List<GrocerySubstitutionProposal> findAllByGroceryOrderId(UUID groceryOrderId);

  List<GrocerySubstitutionProposal> findAllByGroceryOrderIdAndProposalStatus(
      UUID groceryOrderId, SubstitutionProposalStatus proposalStatus);

  long countByGroceryOrderIdAndProposalStatus(
      UUID groceryOrderId, SubstitutionProposalStatus proposalStatus);
}
