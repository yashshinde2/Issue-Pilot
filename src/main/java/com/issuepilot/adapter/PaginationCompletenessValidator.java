package com.issuepilot.adapter;

/**
 * Authoritative Pagination Completeness Validator conforming to Algorithm V2.3 Section 2 & 3.
 *
 * Rules:
 * - A signal is unavailable if pagination was incomplete, truncated, or interrupted by rate limits/errors.
 * - Incomplete evidence must NEVER be treated as complete or as a favorable known zero.
 * - A missing Link header on GitHub/Codeberg indicates a complete single-page response when the item count
 *   is within page limits and no error occurred; it must NOT be marked unavailable.
 * - Multi-page responses whose final page lacks rel="next" are complete.
 * - Interrupted, rate-limited, or malformed pagination must produce isComplete = false.
 */
public final class PaginationCompletenessValidator {

    private PaginationCompletenessValidator() {}

    public record PaginationStatus(
            boolean isComplete,
            boolean hasMorePages,
            int itemsRetrieved,
            String reason
    ) {
        // Compatibility constructor for legacy calls: (isComplete, pagesRetrieved, itemsRetrieved, reason)
        public PaginationStatus(boolean isComplete, int pagesRetrieved, int itemsRetrieved, String reason) {
            this(isComplete, !isComplete, itemsRetrieved, reason);
        }

        public int pagesRetrieved() {
            return 1;
        }

        public String terminationReason() {
            return reason;
        }

        public static PaginationStatus complete(int pages, int items) {
            return new PaginationStatus(true, false, items, "Exhausted all available pages naturally");
        }

        public static PaginationStatus rateLimited(int pages, int items, int httpStatus) {
            return new PaginationStatus(false, true, items, "Pagination truncated by rate limit / error (HTTP " + httpStatus + ")");
        }

        public static PaginationStatus error(int pages, int items, String message) {
            return new PaginationStatus(false, true, items, "Pagination aborted: " + message);
        }

        public static PaginationStatus empty() {
            return new PaginationStatus(true, false, 0, "Empty result set verified complete");
        }

        public static PaginationStatus completeSinglePage(int items) {
            return new PaginationStatus(true, false, items, "Single page result verified complete (no further pages)");
        }

        public static PaginationStatus completeMultiPage(int items) {
            return new PaginationStatus(true, false, items, "Multi-page traversal reached final page successfully (no rel=\"next\")");
        }

        public static PaginationStatus inProgress(int items) {
            return new PaginationStatus(false, true, items, "Pagination in progress; more pages available");
        }

        public static PaginationStatus truncated(int items, String reason) {
            return new PaginationStatus(false, true, items, "Pagination truncated: " + reason);
        }

        public static PaginationStatus malformed(String reason) {
            return new PaginationStatus(false, false, 0, "Malformed pagination metadata: " + reason);
        }
    }

    /**
     * Evaluates GitHub and Codeberg RFC 5988 Link header pagination.
     *
     * @param linkHeader     RFC 5988 Link header string (null if single-page or absent)
     * @param itemsReturned  Number of items returned in current response
     * @param perPage        Page size requested
     * @param interrupted    True if request loop was halted before natural exhaustion (e.g. rate limit, budget cap)
     * @param httpStatus     HTTP response status code (e.g. 200, 429, 500)
     */
    public static PaginationStatus evaluateGitHubLinkPagination(
            String linkHeader,
            int itemsReturned,
            int perPage,
            boolean interrupted,
            int httpStatus
    ) {
        if (httpStatus == 429 || httpStatus == 403) {
            return PaginationStatus.truncated(itemsReturned, "Rate limit or permission error encountered (HTTP " + httpStatus + ")");
        }
        if (httpStatus != 200) {
            return PaginationStatus.truncated(itemsReturned, "HTTP request failed with status " + httpStatus);
        }
        if (interrupted) {
            return PaginationStatus.truncated(itemsReturned, "Traversal halted before exhausting available pages");
        }
        if (perPage <= 0 || itemsReturned < 0) {
            return PaginationStatus.malformed("Invalid page size (" + perPage + ") or item count (" + itemsReturned + ")");
        }

        // Case 1: No Link header present
        // On GitHub and Codeberg, responses that fit in a single page omit the Link header entirely.
        if (linkHeader == null || linkHeader.isBlank()) {
            return PaginationStatus.completeSinglePage(itemsReturned);
        }

        // Case 2: Link header present and contains rel="next" -> more pages exist
        if (linkHeader.contains("rel=\"next\"")) {
            return PaginationStatus.inProgress(itemsReturned);
        }

        // Case 3: Link header present but has NO rel="next" (e.g. contains rel="prev", rel="first")
        // This confirms the client has successfully fetched the final page of a multi-page series.
        if (linkHeader.contains("rel=\"prev\"") || linkHeader.contains("rel=\"first\"")) {
            return PaginationStatus.completeMultiPage(itemsReturned);
        }

        // Unparseable Link header structure
        return PaginationStatus.malformed("Unrecognized Link header format: " + linkHeader);
    }

    /**
     * Evaluates GitLab header-based pagination (X-Next-Page, X-Total-Pages).
     *
     * @param xNextPage      Value of X-Next-Page header (null, empty, or string page number)
     * @param itemsReturned  Items in current response
     * @param interrupted    True if loop was interrupted early
     * @param httpStatus     HTTP status code
     */
    public static PaginationStatus evaluateGitLabPagination(
            String xNextPage,
            int itemsReturned,
            boolean interrupted,
            int httpStatus
    ) {
        if (httpStatus == 429 || httpStatus == 403) {
            return PaginationStatus.truncated(itemsReturned, "GitLab rate limit or permission error (HTTP " + httpStatus + ")");
        }
        if (httpStatus != 200) {
            return PaginationStatus.truncated(itemsReturned, "GitLab HTTP error " + httpStatus);
        }
        if (interrupted) {
            return PaginationStatus.truncated(itemsReturned, "GitLab traversal halted before natural exhaustion");
        }

        // No X-Next-Page or blank -> natural termination (single-page or final page)
        if (xNextPage == null || xNextPage.isBlank()) {
            return PaginationStatus.completeSinglePage(itemsReturned);
        }

        try {
            int next = Integer.parseInt(xNextPage.trim());
            if (next <= 0) {
                return PaginationStatus.completeSinglePage(itemsReturned);
            }
            return PaginationStatus.inProgress(itemsReturned);
        } catch (NumberFormatException e) {
            return PaginationStatus.malformed("Non-numeric X-Next-Page header: '" + xNextPage + "'");
        }
    }

    /**
     * Evaluates Bugzilla offset/limit based pagination.
     *
     * @param offset        Starting offset of current page
     * @param limit         Limit/page size requested
     * @param totalMatches  total_matches field from Bugzilla REST response (or null if missing)
     * @param itemsReturned Items returned in current page
     * @param interrupted   True if loop was interrupted
     * @param httpStatus    HTTP status code
     */
    public static PaginationStatus evaluateBugzillaPagination(
            Integer offset,
            Integer limit,
            Integer totalMatches,
            int itemsReturned,
            boolean interrupted,
            int httpStatus
    ) {
        if (httpStatus == 401 || httpStatus == 403) {
            return PaginationStatus.truncated(itemsReturned, "Bugzilla authorization failure (HTTP " + httpStatus + ")");
        }
        if (httpStatus != 200) {
            return PaginationStatus.truncated(itemsReturned, "Bugzilla HTTP error " + httpStatus);
        }
        if (interrupted) {
            return PaginationStatus.truncated(itemsReturned, "Bugzilla query interrupted before reaching total matches");
        }
        if (offset == null || limit == null || totalMatches == null) {
            return PaginationStatus.malformed("Missing offset, limit, or total_matches parameter");
        }
        if (offset < 0 || limit <= 0 || totalMatches < 0) {
            return PaginationStatus.malformed("Negative offset/totalMatches or non-positive limit");
        }

        if (offset + itemsReturned >= totalMatches) {
            return PaginationStatus.completeMultiPage(itemsReturned);
        }
        return PaginationStatus.inProgress(itemsReturned);
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
