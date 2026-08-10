package com.company.aiqa.model;

/**
 * One commit between a merge's pre-merge state and the merge itself
 * (or more generally, between any baseRef and headRef).
 */
public record CommitInfo(
        String sha,
        String shortSha,
        String authorName,
        String authorEmail,
        String commitDate,   // ISO-8601
        String message
) {
}
