package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.service.internal.RebaseOrchestrator;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class RebaseOrchestratorTest {

  @Test
  void retries_once_then_succeeds() {
    RecipeWriteApi api = mock(RecipeWriteApi.class);
    AdaptationConfig config = configWith(3);
    RebaseOrchestrator orch = new RebaseOrchestrator(api, config);

    RecipeVersionDto out = mock(RecipeVersionDto.class);
    when(api.saveAdaptedVersion(any()))
        .thenThrow(new RecipeVersionConflictException("conflict"))
        .thenReturn(out);

    SaveAdaptedVersionCommand cmd = stubCommand();
    UnaryOperator<SaveAdaptedVersionCommand> rebase = c -> stubCommand();
    RecipeVersionDto result = orch.saveAdaptedVersionWithRebase(cmd, rebase);
    assertThat(result).isSameAs(out);
    verify(api, times(2)).saveAdaptedVersion(any());
  }

  @Test
  void throws_exhausted_after_max_attempts() {
    RecipeWriteApi api = mock(RecipeWriteApi.class);
    AdaptationConfig config = configWith(2);
    RebaseOrchestrator orch = new RebaseOrchestrator(api, config);
    when(api.saveAdaptedVersion(any())).thenThrow(new RecipeVersionConflictException("c"));

    assertThatThrownBy(() -> orch.saveAdaptedVersionWithRebase(stubCommand(), c -> stubCommand()))
        .isInstanceOf(RebaseExhaustedException.class);
    verify(api, times(2)).saveAdaptedVersion(any());
  }

  @Test
  void single_attempt_with_max_attempts_one_throws_exhausted_on_first_conflict() {
    RecipeWriteApi api = mock(RecipeWriteApi.class);
    AdaptationConfig config = configWith(1);
    RebaseOrchestrator orch = new RebaseOrchestrator(api, config);
    when(api.saveAdaptedVersion(any())).thenThrow(new RecipeVersionConflictException("c"));
    assertThatThrownBy(() -> orch.saveAdaptedVersionWithRebase(stubCommand(), c -> stubCommand()))
        .isInstanceOf(RebaseExhaustedException.class);
    verify(api, times(1)).saveAdaptedVersion(any());
  }

  private static SaveAdaptedVersionCommand stubCommand() {
    return new SaveAdaptedVersionCommand(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        "reason",
        UUID.randomUUID());
  }

  private static AdaptationConfig configWith(int maxAttempts) {
    return new AdaptationConfig(
        5,
        10_000,
        8_000,
        12_000,
        maxAttempts,
        3,
        14,
        new BigDecimal("0.50"),
        new BigDecimal("2.00"),
        null,
        30,
        "0 0 4 * * *",
        "0 30 4 * * *");
  }
}
