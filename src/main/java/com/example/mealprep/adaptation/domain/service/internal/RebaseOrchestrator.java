package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wraps {@link RecipeWriteApi} write calls with optimistic-conflict retries. On {@link
 * RecipeVersionConflictException} the supplied rebase function rebuilds the command with refreshed
 * expectations; up to {@link AdaptationConfig#maxRebaseAttempts()} attempts before throwing {@link
 * RebaseExhaustedException}.
 *
 * <p>Per ticket 01c §Step 7 / LLD lines 762-763.
 */
@Component
public class RebaseOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(RebaseOrchestrator.class);

  private final RecipeWriteApi writeApi;
  private final AdaptationConfig config;

  public RebaseOrchestrator(RecipeWriteApi writeApi, AdaptationConfig config) {
    this.writeApi = writeApi;
    this.config = config;
  }

  /** Save a new version with rebase-on-conflict retries. */
  public RecipeVersionDto saveAdaptedVersionWithRebase(
      SaveAdaptedVersionCommand initial, UnaryOperator<SaveAdaptedVersionCommand> rebase) {
    SaveAdaptedVersionCommand cmd = initial;
    int max = Math.max(1, config.maxRebaseAttempts());
    for (int attempt = 1; attempt <= max; attempt++) {
      try {
        return writeApi.saveAdaptedVersion(cmd);
      } catch (RecipeVersionConflictException e) {
        if (attempt == max) {
          throw new RebaseExhaustedException("rebase exhausted after " + attempt + " attempts", e);
        }
        LOG.warn("rebase attempt {} of {} on saveAdaptedVersion", attempt, max);
        cmd = rebase.apply(cmd);
      }
    }
    throw new IllegalStateException("unreachable");
  }

  /** Save a new branch with rebase-on-conflict retries. */
  public RecipeBranchDto saveAdaptedBranchWithRebase(
      SaveAdaptedBranchCommand initial, UnaryOperator<SaveAdaptedBranchCommand> rebase) {
    SaveAdaptedBranchCommand cmd = initial;
    int max = Math.max(1, config.maxRebaseAttempts());
    for (int attempt = 1; attempt <= max; attempt++) {
      try {
        return writeApi.saveAdaptedBranch(cmd);
      } catch (RecipeVersionConflictException e) {
        if (attempt == max) {
          throw new RebaseExhaustedException("rebase exhausted after " + attempt + " attempts", e);
        }
        LOG.warn("rebase attempt {} of {} on saveAdaptedBranch", attempt, max);
        cmd = rebase.apply(cmd);
      }
    }
    throw new IllegalStateException("unreachable");
  }
}
