package com.saipramod.gitcontext.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param repoPath         local checkout to index on startup
 * @param includeCommits   index recent commit messages as chunks
 * @param minEvidence      retrieval-failure guard: abstain below this evidence score
 * @param stalenessCheckMs how often to look for changed files (0 disables)
 * @param llmReranker      use the LLM as reranker when a chat model is configured
 */
@ConfigurationProperties(prefix = "gitcontext")
public record GitContextProperties(
        String repoPath,
        boolean includeCommits,
        double minEvidence,
        long stalenessCheckMs,
        boolean llmReranker) {

    public GitContextProperties {
        repoPath = repoPath == null || repoPath.isBlank() ? "." : repoPath;
        minEvidence = minEvidence <= 0 ? com.saipramod.gitcontext.service.AskService.DEFAULT_MIN_EVIDENCE : minEvidence;
    }
}
