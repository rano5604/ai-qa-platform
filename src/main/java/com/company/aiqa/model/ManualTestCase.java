package com.company.aiqa.model;

/**
 * One functional/business test case for a human QA tester to execute -
 * describes expected feature behavior, not test code. Generated directly by
 * the LLM from the actual diff/impact context, independent of whether any
 * automated tests exist.
 *
 * action/existingTestCaseId let the pipeline distinguish a genuinely new
 * scenario from a revision of one that already exists in the master test
 * case catalog (see ManualTestCaseGenerator.upsertMasterCsv):
 *   - action="NEW":    a scenario not covered by anything already known -
 *                       existingTestCaseId is blank.
 *   - action="UPDATE": this diff changes behavior an existing case already
 *                       covers - existingTestCaseId is that case's ID in the
 *                       master catalog, and every other field here is the
 *                       corrected version of that case.
 *   - action="EXISTING": used only in-memory when loading the current
 *                       catalog back in as LLM context; never written out
 *                       as-is (see ManualTestCaseGenerator.loadMasterCsv).
 */
public record ManualTestCase(
        String testCaseId,
        String feature,
        String scenario,
        String preconditions,
        String steps,
        String testData,
        String expectedResult,
        String priority,   // High / Medium / Low
        String type,       // Positive / Negative / Boundary / Security
        String relatedFile,
        String action,             // NEW / UPDATE / EXISTING
        String existingTestCaseId  // set when action=="UPDATE" (or "EXISTING"); blank for NEW
) {
}
