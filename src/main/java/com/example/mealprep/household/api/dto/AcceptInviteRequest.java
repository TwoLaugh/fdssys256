package com.example.mealprep.household.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/invites/accept}. */
public record AcceptInviteRequest(@NotBlank @Size(max = 32) String inviteCode) {}
