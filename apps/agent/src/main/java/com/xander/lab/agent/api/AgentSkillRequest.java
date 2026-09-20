package com.xander.lab.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Create request for a user-private, non-executable agent Skill. */
@Data
public class AgentSkillRequest {
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,79}$")
    private String skillKey;
    @NotBlank @Size(max = 120) private String name;
    @NotBlank @Size(max = 500) private String description;
    @NotBlank @Size(max = 12000) private String instructions;
    @Size(max = 30) private List<@Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$") String> toolNames = List.of();
}
