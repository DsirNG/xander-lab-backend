package com.xander.lab.agent.application;

import java.util.Set;

/** A trusted, versioned product capability that composes instructions and tools for an agent turn. */
public interface AgentSkill {

    String id();

    String version();

    /** Returns a score for the user's goal; zero means that the skill is not a candidate. */
    int matchScore(String userMessage);

    /** Tools that may be exposed when this skill is active. */
    Set<String> toolNames();

    /** Additional instructions for the model while this skill is active. */
    String instructions();

    /** Human-readable reason retained in the run snapshot for support and evaluation. */
    String matchReason(String userMessage);
}
