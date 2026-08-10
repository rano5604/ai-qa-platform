package com.company.aiqa.model;

/**
 * Which output(s) a pipeline run produces.
 *
 * MANUAL:    functional/business test cases for QA to execute by hand -
 *            describes what the feature should do, not how the code is
 *            structured. This is the default, since QA validates business
 *            behavior and generally doesn't need unit-test code.
 * AUTOMATED: JUnit/Jest/pytest/etc. source files (the original behavior).
 * BOTH:      generates both outputs (two separate LLM calls).
 */
public enum TestCaseMode {
    MANUAL,
    AUTOMATED,
    BOTH
}
