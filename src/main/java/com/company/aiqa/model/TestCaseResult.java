package com.company.aiqa.model;

/**
 * One generated test file (language/framework depends on its target), as returned by the LLM and written to disk.
 */
public record TestCaseResult(
        String targetClassName,
        String testFileName,
        String testCode,
        String writtenPath
) {
}
