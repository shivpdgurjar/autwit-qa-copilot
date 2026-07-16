package com.autwit.copilot.artifact;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** openapi.yaml CreateArtifactRequest — a tester attaching evidence directly. */
public record CreateArtifactRequest(
        @NotBlank String artifactType,
        @NotBlank String sourceSystem,
        @NotBlank String logicalName,
        @NotNull ArtifactFormat format,
        @NotNull Object body,
        Integer rowCount,
        String contentHash,
        UUID stepId,
        UUID milestoneId,
        Map<String, Object> meta) {
}
