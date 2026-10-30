package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.issuepilot.config.AlgorithmConstants;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic Typed Evidence Envelope conforming to Algorithm V2.3 specification Section 2.
 *
 * Available/Unavailable & Known-Zero Semantics:
 * - available = false: value MUST be null (never 0.0 or a default guess), confidence = 0.0.
 * - available = true, value = 0: allowed only when verified measurement proves zero during stated window.
 *
 * @param <T> Payload type representing measured signal.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Evidence<T> {

    @JsonProperty("value")
    private T value;

    @JsonProperty("available")
    private boolean available = false;

    @JsonProperty("confidence")
    private double confidence = 0.0;

    @JsonProperty("source")
    private String source;

    @JsonProperty("source_instance")
    private String sourceInstance;

    @JsonProperty("method")
    private String method;

    @JsonProperty("sample_size")
    private Integer sampleSize;

    @JsonProperty("coverage")
    private Double coverage;

    @JsonProperty("observed_at")
    private String observedAt;

    @JsonProperty("window_start")
    private String windowStart;

    @JsonProperty("window_end")
    private String windowEnd;

    @JsonProperty("pagination_complete")
    private boolean paginationComplete = true;

    @JsonProperty("approximation_reason")
    private String approximationReason;

    @JsonProperty("evidence_refs")
    private List<String> evidenceRefs = new ArrayList<>();

    @JsonProperty("schema_version")
    private String schemaVersion = AlgorithmConstants.FEATURE_SCHEMA_VERSION;

    @JsonProperty("confidence_factors")
    private ConfidenceFactors confidenceFactors;

    public Evidence() {
        this.observedAt = Instant.now().toString();
    }

    /**
     * Factory for defensibly measured positive or structured evidence.
     */
    public static <T> Evidence<T> of(T value, double confidence, String source, String sourceInstance, String method) {
        if (value == null) {
            throw new IllegalArgumentException("Available evidence must not have a null value; use Evidence.unavailable() for missing signals.");
        }
        Evidence<T> e = new Evidence<>();
        e.value = value;
        e.available = true;
        e.confidence = clampConfidence(confidence);
        e.source = source;
        e.sourceInstance = sourceInstance;
        e.method = method;
        e.paginationComplete = true;
        return e;
    }

    /**
     * Factory for verified zero evidence (distinguishes proven zero from unavailable/missing).
     */
    public static <T> Evidence<T> knownZero(double confidence, String source, String sourceInstance, String method, T zeroValue) {
        if (zeroValue == null) {
            throw new IllegalArgumentException("Known zero evidence must have a non-null zero value.");
        }
        Evidence<T> e = new Evidence<>();
        e.value = zeroValue;
        e.available = true;
        e.confidence = clampConfidence(confidence);
        e.source = source;
        e.sourceInstance = sourceInstance;
        e.method = method;
        e.paginationComplete = true;
        return e;
    }

    /**
     * Factory for unavailable signals (missing, incomplete, permission-failed, or unverified).
     */
    public static <T> Evidence<T> unavailable(String reason, String source, String sourceInstance) {
        Evidence<T> e = new Evidence<>();
        e.value = null;
        e.available = false;
        e.confidence = 0.0;
        e.source = source;
        e.sourceInstance = sourceInstance;
        e.approximationReason = reason;
        e.paginationComplete = false;
        return e;
    }

    /**
     * Factory computing composite confidence c = min(source_cap, c_sample * c_coverage * c_directness).
     */
    public static <T> Evidence<T> withCalculatedConfidence(T value,
                                                            double cSample,
                                                            double cCoverage,
                                                            double cDirectness,
                                                            double sourceCap,
                                                            String source,
                                                            String sourceInstance,
                                                            String method) {
        ConfidenceFactors factors = new ConfidenceFactors(cSample, cCoverage, cDirectness, sourceCap);
        double c = computeConfidence(cSample, cCoverage, cDirectness, sourceCap);
        Evidence<T> e = of(value, c, source, sourceInstance, method);
        e.setConfidenceFactors(factors);
        return e;
    }

    /**
     * Strictly validates confidence in [0.0, 1.0]. Rejects non-finite, negative, or > 1.0 values.
     */
    public static double validateConfidence(double c) {
        if (Double.isNaN(c) || Double.isInfinite(c) || c < 0.0 || c > 1.0) {
            throw new IllegalArgumentException("Confidence must be a finite number in [0.0, 1.0], got: " + c);
        }
        return c;
    }

    public static double clampConfidence(double c) {
        return validateConfidence(c);
    }

    public static double computeConfidence(double cSample, double cCoverage, double cDirectness, double sourceCap) {
        double s = validateConfidence(cSample);
        double cov = validateConfidence(cCoverage);
        double dir = validateConfidence(cDirectness);
        double cap = validateConfidence(sourceCap);
        double raw = s * cov * dir;
        return Math.min(cap, raw);
    }

    /**
     * Computes duration between two ISO-8601 UTC timestamps, rejecting negative durations
     * except small clock skew (up to maxToleranceMillis) which is clamped to zero.
     */
    public static Duration computeSafeDuration(String startUtc, String endUtc, long maxToleranceMillis) {
        if (startUtc == null || endUtc == null) {
            throw new IllegalArgumentException("Timestamps must not be null");
        }
        Instant start = Instant.parse(startUtc);
        Instant end = Instant.parse(endUtc);
        long millis = Duration.between(start, end).toMillis();
        if (millis < 0) {
            if (millis >= -maxToleranceMillis) {
                // Tolerated clock skew
                return Duration.ZERO;
            }
            throw new IllegalArgumentException("Negative duration detected beyond tolerated clock skew: " + millis + " ms");
        }
        return Duration.ofMillis(millis);
    }

    // Getters and Setters

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        if (value != null && !this.available) {
            this.available = true;
        }
        this.value = value;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
        if (!available) {
            this.value = null;
            this.confidence = 0.0;
        }
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = clampConfidence(confidence);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceInstance() {
        return sourceInstance;
    }

    public void setSourceInstance(String sourceInstance) {
        this.sourceInstance = sourceInstance;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Integer getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(Integer sampleSize) {
        this.sampleSize = sampleSize;
    }

    public Double getCoverage() {
        return coverage;
    }

    public void setCoverage(Double coverage) {
        this.coverage = coverage;
    }

    public String getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(String observedAt) {
        this.observedAt = observedAt;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart;
    }

    public String getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(String windowEnd) {
        this.windowEnd = windowEnd;
    }

    public boolean isPaginationComplete() {
        return paginationComplete;
    }

    public void setPaginationComplete(boolean paginationComplete) {
        this.paginationComplete = paginationComplete;
        if (!paginationComplete) {
            this.available = false;
            this.value = null;
            this.confidence = 0.0;
        }
    }

    public String getApproximationReason() {
        return approximationReason;
    }

    public void setApproximationReason(String approximationReason) {
        this.approximationReason = approximationReason;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs != null ? evidenceRefs : new ArrayList<>();
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public ConfidenceFactors getConfidenceFactors() {
        return confidenceFactors;
    }

    public void setConfidenceFactors(ConfidenceFactors confidenceFactors) {
        this.confidenceFactors = confidenceFactors;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfidenceFactors {
        @JsonProperty("c_sample")
        private double cSample = 1.0;

        @JsonProperty("c_coverage")
        private double cCoverage = 1.0;

        @JsonProperty("c_directness")
        private double cDirectness = 1.0;

        @JsonProperty("source_cap")
        private double sourceCap = 1.0;

        public ConfidenceFactors() {}

        public ConfidenceFactors(double cSample, double cCoverage, double cDirectness, double sourceCap) {
            this.cSample = clampConfidence(cSample);
            this.cCoverage = clampConfidence(cCoverage);
            this.cDirectness = clampConfidence(cDirectness);
            this.sourceCap = clampConfidence(sourceCap);
        }

        public double getCSample() { return cSample; }
        public void setCSample(double cSample) { this.cSample = clampConfidence(cSample); }
        public double getCCoverage() { return cCoverage; }
        public void setCCoverage(double cCoverage) { this.cCoverage = clampConfidence(cCoverage); }
        public double getCDirectness() { return cDirectness; }
        public void setCDirectness(double cDirectness) { this.cDirectness = clampConfidence(cDirectness); }
        public double getSourceCap() { return sourceCap; }
        public void setSourceCap(double sourceCap) { this.sourceCap = clampConfidence(sourceCap); }
    }
}

