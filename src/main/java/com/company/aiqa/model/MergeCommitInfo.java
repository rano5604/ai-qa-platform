package com.company.aiqa.model;

/**
 * A merge commit found on a branch, together with the first-parent commit
 * it merged into - i.e. exactly the before/after pair needed to diff
 * "what did this merge actually change".
 */
public record MergeCommitInfo(
        String mergeSha,
        String preMergeSha
) {
}
