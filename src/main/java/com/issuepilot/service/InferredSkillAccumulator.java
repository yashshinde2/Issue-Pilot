package com.issuepilot.service;

import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;

import java.util.*;

/**
 * Accumulator that aggregates language statistics and manifest evidence for a single technology,
 * computing a deterministic, bounded confidence score.
 */
public class InferredSkillAccumulator {

    private final String technology;
    private final String category;
    private final Set<SkillEvidence> evidenceSet = new LinkedHashSet<>();
    private final Set<String> observedRepositories = new HashSet<>();
    private long totalBytes = 0;
    private double maxBytePercentage = 0.0;
    private boolean foundInProductionManifest = false;
    private boolean foundInDevManifest = false;

    public InferredSkillAccumulator(String technology, String category) {
        this.technology = technology;
        this.category = category;
    }

    public void addEvidence(SkillEvidence evidence, double bytePercent, long bytes) {
        this.evidenceSet.add(evidence);
        this.observedRepositories.add(evidence.getRepository());
        this.totalBytes += bytes;
        this.maxBytePercentage = Math.max(this.maxBytePercentage, bytePercent);
    }

    public void addManifestEvidence(SkillEvidence evidence, boolean isDev) {
        this.evidenceSet.add(evidence);
        this.observedRepositories.add(evidence.getRepository());
        if (isDev) {
            this.foundInDevManifest = true;
        } else {
            this.foundInProductionManifest = true;
        }
    }

    // Documented Confidence Scoring Formula:
    // - Language evidence base: 0.50
    //   + 0.20 if >= 50% repo bytes or in >= 3 repos
    //   + 0.10 if >= 20% repo bytes or in >= 2 repos
    // - Manifest evidence base: 0.70
    //   + 0.15 if in >= 2 repos
    //   - 0.15 if only in devDependencies
    // - Combination bonus: + 0.10 if technology is supported by both language and manifest
    // - Bounds: strictly clamped between 0.10 and 0.95.
    public double calculateConfidence() {
        double confidence = 0.50;

        if (foundInProductionManifest) {
            confidence = 0.70;
            if (observedRepositories.size() >= 2) {
                confidence += 0.15;
            }
        } else if (foundInDevManifest) {
            confidence = 0.55;
        } else if (totalBytes > 0) {
            if (maxBytePercentage >= 50.0 || observedRepositories.size() >= 3) {
                confidence = 0.75;
            } else if (maxBytePercentage >= 20.0 || observedRepositories.size() >= 2) {
                confidence = 0.65;
            } else if (maxBytePercentage < 5.0 && observedRepositories.size() == 1) {
                confidence = 0.40;
            }
        }

        // Multiple repos or both sources bonus
        if (foundInProductionManifest && totalBytes > 0) {
            confidence += 0.10;
        }

        return Math.max(0.10, Math.min(0.95, Math.round(confidence * 100.0) / 100.0));
    }

    public InferredSkill toInferredSkill() {
        InferredSkill skill = new InferredSkill(technology, category, calculateConfidence());
        skill.setStatus("pending_confirmation");
        skill.setEvidence(new ArrayList<>(evidenceSet));
        return skill;
    }
}

