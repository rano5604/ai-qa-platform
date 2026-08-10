package com.company.aiqa.model;

/**
 * The pipeline's result for a single merge processed during a backfill run.
 */
public record MergeRunResult(
        String mergeSha,
        String preMergeSha,
        String processedAt,   // ISO-8601
        GenerateTestsResponse response
) {
}
