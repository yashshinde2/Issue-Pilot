package com.issuepilot.difficulty;

import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2.DifficultyComputationResult;
import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2.FitComputationResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Production implementation of Issue Pilot V2.3 Section 6: Difficulty Estimator
 * and Engineering Challenge Fit Model.
 *
 * Requirements:
 * - Four evidence-backed signals: Scope (30%), Technical (30%), Dependency (20%), Coordination (20%).
 * - Rubric anchors strictly from {0, 25, 50, 75, 100}.
 * - Unsupported signals remain unavailable; never defaulted to zero.
 * - Coverage threshold: A_ID >= 0.50 required.
 * - Weak label corroboration clamped to [-3, +3].
 * - Fit model: g = ID - cap_eff, with 5-point dead zone and 1.5 hard-side slope.
 * - S = contributor capability; if S unavailable, D is unavailable.
 */
@Service
public class DifficultyEstimatorService {

    public static final String DIFFICULTY_ESTIMATOR_VERSION = "2.3.0";

    // Negation patterns to prevent false complexity triggers
    private static final Pattern NEGATION_MIGRATION = Pattern.compile(
            "\\b(?:no|without|not)\\s+(?:database\\s+)?migration\\s+required\\b|\\bno\\s+migration\\s+needed\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NEGATION_ARCH_IMPACT = Pattern.compile(
            "\\b(?:no|without)\\s+(?:architectural|breaking|api)\\s+(?:change|impact|effect)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NEGATION_COORDINATION = Pattern.compile(
            "\\b(?:no|without)\\s+(?:coordination|cross-team|external\\s+release)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Evaluates explicit difficulty evidence and applies weak label corroboration.
     */
    public DifficultyComputationResult evaluateDifficulty(DifficultyEvidenceV2 evidence, List<String> labels) {
        if (evidence == null) {
            return DifficultyComputationResult.unavailable(0.0, "No difficulty evidence provided.");
        }
        return evidence.computeDifficulty(labels);
    }

    /**
     * Context-aware extraction of difficulty evidence from issue fields.
     */
    public DifficultyEvidenceV2 extractEvidenceFromContent(
            String title,
            String body,
            List<String> labels,
            Integer changedFilesCount
    ) {
        DifficultyEvidenceV2 ev = new DifficultyEvidenceV2();
        String fullText = (title != null ? title : "") + "\n" + (body != null ? body : "");
        String lower = fullText.toLowerCase(Locale.ROOT);

        // 1. Scope Complexity (SC, 30%)
        if (changedFilesCount != null) {
            ev.setScopeAvailable(true);
            ev.setScopeDirectness("explicit_structured");
            ev.setScopeConfidence(1.0);
            if (changedFilesCount == 0) ev.setScopeChangeSize(0.0);
            else if (changedFilesCount == 1) ev.setScopeChangeSize(25.0);
            else if (changedFilesCount <= 5) ev.setScopeChangeSize(50.0);
            else if (changedFilesCount <= 15) ev.setScopeChangeSize(75.0);
            else ev.setScopeChangeSize(100.0);
        } else if (lower.contains("single file") || lower.contains("localized fix") || lower.contains("typo in")) {
            ev.setScopeAvailable(true);
            ev.setScopeDirectness("unambiguous_prose");
            ev.setScopeConfidence(0.80);
            ev.setScopeChangeSize(25.0);
        } else if (lower.contains("cross-system") || lower.contains("broad refactoring") || lower.contains("entire codebase")) {
            ev.setScopeAvailable(true);
            ev.setScopeDirectness("unambiguous_prose");
            ev.setScopeConfidence(0.80);
            ev.setScopeChangeSize(100.0);
        }

        // 2. Technical Complexity (TC, 30%)
        if (lower.contains("config only") || lower.contains("typo fix") || lower.contains("update readme")) {
            ev.setTechnicalAvailable(true);
            ev.setTechnicalDirectness("unambiguous_prose");
            ev.setTechnicalConfidence(0.80);
            ev.setTechnicalComplexity(0.0);
        } else if (lower.contains("security vulnerability") || lower.contains("concurrency bug") ||
                   lower.contains("race condition") || lower.contains("deadlock") || lower.contains("protocol complexity")) {
            ev.setTechnicalAvailable(true);
            ev.setTechnicalDirectness("unambiguous_prose");
            ev.setTechnicalConfidence(0.80);
            ev.setTechnicalComplexity(75.0);
        } else if (lower.contains("routine fix") || lower.contains("minor bug") || lower.contains("small improvement")) {
            ev.setTechnicalAvailable(true);
            ev.setTechnicalDirectness("unambiguous_prose");
            ev.setTechnicalConfidence(0.80);
            ev.setTechnicalComplexity(25.0);
        }

        // 3. Dependency / Architectural Impact (DI, 20%)
        if (NEGATION_MIGRATION.matcher(fullText).find() || NEGATION_ARCH_IMPACT.matcher(fullText).find()) {
            ev.setDependencyAvailable(true);
            ev.setDependencyDirectness("unambiguous_prose");
            ev.setDependencyConfidence(0.80);
            ev.setDependencyArchImpact(0.0); // Negation explicitly yields anchor 0.0
        } else if (lower.contains("database migration required") || lower.contains("schema migration") || lower.contains("breaking api change")) {
            ev.setDependencyAvailable(true);
            ev.setDependencyDirectness("unambiguous_prose");
            ev.setDependencyConfidence(0.80);
            ev.setDependencyArchImpact(75.0);
        }

        // 4. Coordination Complexity (CC, 20%)
        if (NEGATION_COORDINATION.matcher(fullText).find()) {
            ev.setCoordinationAvailable(true);
            ev.setCoordinationDirectness("unambiguous_prose");
            ev.setCoordinationConfidence(0.80);
            ev.setCoordinationComplexity(0.0);
        } else if (lower.contains("cross-team dependency") || lower.contains("cross-repo coordination") || lower.contains("coordinated release")) {
            ev.setCoordinationAvailable(true);
            ev.setCoordinationDirectness("unambiguous_prose");
            ev.setCoordinationConfidence(0.80);
            ev.setCoordinationComplexity(75.0);
        }

        return ev;
    }

    /**
     * Computes the Engineering Challenge Fit score D between Issue Difficulty and Contributor Capability.
     */
    public FitComputationResult calculateFit(
            Double issueDifficulty, Double confidenceId,
            Double contributorCapability, Double confidenceCap
    ) {
        return DifficultyEvidenceV2.computeFit(issueDifficulty, confidenceId, contributorCapability, confidenceCap);
    }
}

