package com.company.aiqa.model;

/**
 * One changed source file (any supported language) touched between two git refs.
 */
public record ChangedFile(
        String path,
        ChangeType changeType,
        String diffText,
        String fileContent
) {
    public enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED }
}
