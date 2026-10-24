package com.issuepilot.adapter;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AdapterRegistry {

    private final Map<String, BaseIssueSourceAdapter> adapters = new LinkedHashMap<>();

    public AdapterRegistry(
            GitHubAdapter gitHubAdapter,
            GitLabAdapter gitLabAdapter,
            CodebergAdapter codebergAdapter,
            BugzillaAdapter bugzillaAdapter
    ) {
        adapters.put("github", gitHubAdapter);
        adapters.put("gitlab", gitLabAdapter);
        adapters.put("codeberg", codebergAdapter);
        adapters.put("bugzilla", bugzillaAdapter);
    }

    public BaseIssueSourceAdapter getAdapter(String sourceName) {
        String key = (sourceName != null ? sourceName.trim().toLowerCase() : "github");
        BaseIssueSourceAdapter adapter = adapters.get(key);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown issue source platform: '" + sourceName + "'. Supported: " + adapters.keySet());
        }
        return adapter;
    }

    public Map<String, BaseIssueSourceAdapter> getAllAdapters() {
        return Collections.unmodifiableMap(adapters);
    }
}
