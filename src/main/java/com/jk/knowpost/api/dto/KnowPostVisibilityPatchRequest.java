package com.jk.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowPostVisibilityPatchRequest(
        @NotBlank String visible
) {}