package com.issuepilot.adapter;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative Maintainer Role Resolution conforming to V2.3 specification Section 7.
 *
 * Rules:
 * - Maintainer association MUST belong to the actual comment/response author, not the issue creator.
 * - Membership and permission checks must be tied to the specific user and project.
 * - Ambiguous roles, permission errors, and missing membership data MUST yield UNAVAILABLE evidence,
 *   never a guessed or assumed non-maintainer / zero result.
 * - Human events only: Bot accounts are strictly excluded.
 */
public final class MaintainerResolutionHelper {

    private MaintainerResolutionHelper() {}

    private static final Set<String> GITHUB_MAINTAINER_ASSOCIATIONS = Set.of("OWNER", "MEMBER", "COLLABORATOR");
    private static final Set<String> GITHUB_NON_MAINTAINER_ASSOCIATIONS = Set.of("CONTRIBUTOR", "FIRST_TIME_CONTRIBUTOR", "NONE");

    public record RoleResult(
            boolean available,
            boolean isMaintainer,
            boolean isBot,
            double confidence,
            String reason
    ) {
        public static RoleResult maintainer(double confidence, String reason) {
            return new RoleResult(true, true, false, confidence, reason);
        }

        public static RoleResult nonMaintainer(double confidence, String reason) {
            return new RoleResult(true, false, false, confidence, reason);
        }

        public static RoleResult bot(String username) {
            return new RoleResult(true, false, true, 1.0, "Excluded bot account: " + username);
        }

        public static RoleResult unavailable(String reason) {
            return new RoleResult(false, false, false, 0.0, reason);
        }
    }

    /**
     * Resolves GitHub maintainer role from the response COMMENT event, NOT the issue creator.
     */
    public static RoleResult resolveGitHubCommentMaintainer(Map<String, Object> comment) {
        if (comment == null) {
            return RoleResult.unavailable("Comment record is null");
        }

        // 1. Author and Bot check on the comment author
        Object userObj = comment.get("user");
        if (userObj instanceof Map) {
            Map<?, ?> user = (Map<?, ?>) userObj;
            String login = (String) user.get("login");
            String type = (String) user.get("type");
            if ((login != null && login.toLowerCase().endsWith("[bot]")) || "Bot".equalsIgnoreCase(type)) {
                return RoleResult.bot(login != null ? login : "unknown");
            }
        }

        // 2. Author association on the comment
        Object assocObj = comment.get("author_association");
        if (assocObj == null) {
            return RoleResult.unavailable("Missing author_association on response comment");
        }
        String assoc = assocObj.toString().trim().toUpperCase();
        if (assoc.isBlank()) {
            return RoleResult.unavailable("Blank author_association on response comment");
        }

        if (GITHUB_MAINTAINER_ASSOCIATIONS.contains(assoc)) {
            return RoleResult.maintainer(0.90, "Verified maintainer via comment author_association: " + assoc);
        } else if (GITHUB_NON_MAINTAINER_ASSOCIATIONS.contains(assoc)) {
            return RoleResult.nonMaintainer(0.90, "Verified non-maintainer via comment author_association: " + assoc);
        } else {
            return RoleResult.unavailable("Ambiguous author_association on comment: " + assoc);
        }
    }

    /**
     * Resolves GitLab maintainer role tied to specific project and user membership.
     * Permission failures, missing membership data, and ambiguous roles must produce unavailable evidence.
     */
    public static RoleResult resolveGitLabProjectMaintainer(String username,
                                                            Integer projectId,
                                                            Map<String, Object> membershipData,
                                                            Integer httpStatusCode) {
        if (username == null || username.isBlank() || projectId == null) {
            return RoleResult.unavailable("Missing username or project_id for GitLab role verification");
        }

        // Check HTTP permission failure on membership query
        if (httpStatusCode != null && (httpStatusCode == 401 || httpStatusCode == 403)) {
            return RoleResult.unavailable("Permission denied querying project " + projectId + " membership (HTTP " + httpStatusCode + ")");
        }

        if (membershipData == null || membershipData.isEmpty()) {
            return RoleResult.unavailable("Membership data unavailable for user " + username + " in project " + projectId);
        }

        // Bot check
        Boolean isBot = (Boolean) membershipData.get("bot");
        if (Boolean.TRUE.equals(isBot) || username.toLowerCase().contains("bot")) {
            return RoleResult.bot(username);
        }

        Object accessLevelObj = membershipData.get("access_level");
        if (accessLevelObj instanceof Number) {
            int level = ((Number) accessLevelObj).intValue();
            // GitLab standard access levels: 10 Guest, 20 Reporter, 30 Developer, 40 Maintainer, 50 Owner
            if (level >= 40) {
                return RoleResult.maintainer(0.85, "GitLab verified maintainer (access_level=" + level + ")");
            } else if (level > 0) {
                return RoleResult.nonMaintainer(0.85, "GitLab contributor/developer (access_level=" + level + ")");
            }
        }

        return RoleResult.unavailable("Ambiguous or unmapped GitLab access level for user: " + username);
    }

    /**
     * Resolves Codeberg / Forgejo maintainer role tied to specific repository and user.
     */
    public static RoleResult resolveCodebergRepoMaintainer(String username,
                                                           String repoFullName,
                                                           Map<String, Object> collaboratorData,
                                                           Integer httpStatusCode) {
        if (username == null || username.isBlank() || repoFullName == null || repoFullName.isBlank()) {
            return RoleResult.unavailable("Missing username or repo for Codeberg role check");
        }

        if (httpStatusCode != null && (httpStatusCode == 401 || httpStatusCode == 403)) {
            return RoleResult.unavailable("Permission denied querying collaborator status on " + repoFullName + " (HTTP " + httpStatusCode + ")");
        }

        if (collaboratorData == null || collaboratorData.isEmpty()) {
            return RoleResult.unavailable("Collaborator data unavailable for " + username + " on " + repoFullName);
        }

        Object permObj = collaboratorData.get("permissions");
        if (permObj instanceof Map) {
            Map<?, ?> perms = (Map<?, ?>) permObj;
            boolean admin = Boolean.TRUE.equals(perms.get("admin"));
            boolean push = Boolean.TRUE.equals(perms.get("push"));
            if (admin || push) {
                return RoleResult.maintainer(0.80, "Codeberg collaborator with write/admin permission");
            } else {
                return RoleResult.nonMaintainer(0.80, "Codeberg read-only permissions");
            }
        }

        return RoleResult.unavailable("Ambiguous permissions structure for Codeberg user: " + username);
    }

    /**
     * Bugzilla maintainer resolution: strictly unavailable without validated mapping.
     */
    public static RoleResult resolveBugzillaMaintainer(String email, String product, String component, Map<String, String> verifiedRoleMapping) {
        if (email == null || email.isBlank()) {
            return RoleResult.unavailable("Missing Bugzilla user email");
        }
        if (verifiedRoleMapping == null || !verifiedRoleMapping.containsKey(email)) {
            return RoleResult.unavailable("Bugzilla lacks native maintainer role verification for " + email + " in " + product + ":" + component);
        }
        String role = verifiedRoleMapping.get(email);
        if ("MAINTAINER".equalsIgnoreCase(role) || "TRIAGER".equalsIgnoreCase(role)) {
            return RoleResult.maintainer(0.70, "Administrator-verified Bugzilla maintainer mapping");
        }
        return RoleResult.nonMaintainer(0.70, "Administrator-verified Bugzilla non-maintainer role");
    }
}
