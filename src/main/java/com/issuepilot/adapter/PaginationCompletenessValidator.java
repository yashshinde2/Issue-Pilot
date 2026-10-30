package com.issuepilot.adapter;

/**
 * Validates Pagination Completeness across platform API responses conforming to V2.3 Section 2 & 3.
 *
 * Rules:
 * - A signal is unavailable if pagination was incomplete, truncated, or interrupted by rate limits/errors.
 * - Incomplete evidence must NEVER be treated as complete or as a favorable known zero.
 */
public final class PaginationCompletenessValidator {

    private PaginationCompletenessValidator() {}

    public record PaginationStatus(
            boolean isComplete,
            int pagesRetrieved,
            int itemsRetrieved,
            String terminationReason
    ) {
        public static PaginationStatus complete(int pages, int items) {
            return new PaginationStatus(true, pages, items, "Exhausted all available pages naturally");
        }

        public static PaginationStatus rateLimited(int pages, int items, int httpStatus) {
            return new PaginationStatus(false, pages, items, "Pagination truncated by rate limit / error (HTTP " + httpStatus + ")");
        }

        public static PaginationStatus error(int pages, int items, String message) {
            return new PaginationStatus(false, pages, items, "Pagination aborted: " + message);
        }

        public static PaginationStatus empty() {
            return new PaginationStatus(true, 1, 0, "Empty result set verified complete");
        }
    }

    /**
     * Checks whether a GitHub/Codeberg response Link header indicates another page exists.
     */
    public static boolean hasNextPageLinkHeader(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) return false;
        return linkHeader.contains("rel=\"next\"");
    }

    /**
     * Checks whether a GitLab response X-Next-Page header indicates another page exists.
     */
    public static boolean hasNextPageGitLabHeader(String xNextPage) {
        if (xNextPage == null || xNextPage.isBlank()) return false;
        try {
            int next = Integer.parseInt(xNextPage.trim());
            return next > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Checks Bugzilla pagination offset and limit.
     */
    public static boolean hasMoreBugzillaMatches(int offset, int returnedCount, int totalMatches) {
        if (totalMatches <= 0) return false;
        return (offset + returnedCount) < totalMatches;
    }

    /**
     * Evaluates window traversal completeness for time-bounded metrics (e.g. 7d comments, 90d commits).
     * If the query terminates before reaching the window start without exhausting the collection,
     * the pagination is incomplete.
     */
    public static boolean isWindowFullyObserved(boolean reachedOldestInWindow, boolean exhaustedPages) {
        return reachedOldestInWindow || exhaustedPages;
    }
}

