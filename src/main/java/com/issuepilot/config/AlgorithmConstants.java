package com.issuepilot.config;

/**
 * Authoritative constants for Issue Pilot Algorithm V2.3.
 * Freezes schema, taxonomy, registry, and formula constants.
 */
public final class AlgorithmConstants {

    private AlgorithmConstants() {}

    // Versioning Constants
    public static final String ALGORITHM_VERSION = "2.3.0";
    public static final String FEATURE_SCHEMA_VERSION = "2.3.0";
    public static final String TECHNOLOGY_REGISTRY_VERSION = "1.0.0";
    public static final String LABEL_TAXONOMY_VERSION = "1.0.0";

    // Component Nominal Weights
    public static final double WEIGHT_SKILL = 0.35;
    public static final double WEIGHT_DIFFICULTY = 0.25;
    public static final double WEIGHT_RESPONSIVENESS = 0.15;
    public static final double WEIGHT_HEALTH = 0.10;
    public static final double WEIGHT_FEEDBACK = 0.10;
    public static final double WEIGHT_FRESHNESS = 0.05;

    // Availability and Coverage Thresholds
    public static final double MIN_PRIMARY_COVERAGE = 0.40;
    public static final double MIN_TECHNOLOGY_COVERAGE = 0.50;
    public static final double MIN_DIFFICULTY_COVERAGE = 0.50;
    public static final double MIN_RESPONSIVENESS_WEIGHT = 0.45;
    public static final double MIN_HEALTH_WEIGHT = 0.40;

    // Conservative Source Confidence Ceilings (Policy Caps)
    public static final double SOURCE_CAP_GITHUB = 0.90;
    public static final double SOURCE_CAP_GITLAB = 0.85;
    public static final double SOURCE_CAP_FORGEJO = 0.80;
    public static final double SOURCE_CAP_INFERRED_PROXY = 0.50;
    public static final double SOURCE_CAP_BUGZILLA = 0.70;

    // Confidence Penalty Multiplier
    public static final double CONFIDENCE_PENALTY_MULTIPLIER = 5.0;

    // Difficulty Fit Parameters
    public static final double DIFFICULTY_DEAD_ZONE = 5.0;
    public static final double DIFFICULTY_HARD_SLOPE = 1.5;
    public static final double DIFFICULTY_EASY_SLOPE = -1.0;

    // Freshness Half-Life Constants
    public static final double FRESHNESS_AGE_HALF_LIFE_DAYS = 14.0;
    public static final double FRESHNESS_IDLE_HALF_LIFE_DAYS = 7.0;
    public static final double FRESHNESS_AGE_WEIGHT = 0.40;
    public static final double FRESHNESS_ACTIVITY_WEIGHT = 0.60;

    // Vote Affinity Parameters
    public static final double AFFINITY_MAX_ADJUSTMENT = 8.0;
    public static final double AFFINITY_LAMBDA = 2.0;
    public static final double AFFINITY_DECAY_HALF_LIFE_DAYS = 60.0;
    public static final double AFFINITY_CORRELATION_THRESHOLD = 0.85;
    public static final double AFFINITY_DAMPING_FACTOR = 0.50;
}

