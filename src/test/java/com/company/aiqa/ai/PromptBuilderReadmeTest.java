package com.company.aiqa.ai;

import com.company.aiqa.model.ImpactResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README is fed to BOTH generators as architectural/business context. These
 * pin that it lands in each prompt, framed as context rather than authority, and
 * that a project with no README changes nothing.
 */
class PromptBuilderReadmeTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private static final String README = "# TailorBook\nOrders move draft -> confirmed -> delivered.";

    @Test
    void automationPromptIncludesTheReadmeAsContext() {
        String prompt = promptBuilder.buildApiAutomationUserPrompt(
                List.of(), List.of(), List.of(), "", "", README);

        assertTrue(prompt.contains("## Project overview (README)"), prompt);
        assertTrue(prompt.contains("Orders move draft -> confirmed -> delivered"), prompt);
        // Framed as context, not an authority the model can assert against.
        assertTrue(prompt.contains("CONTEXT, not an authority"), prompt);
    }

    @Test
    void businessPromptIncludesTheReadmeEvenWithNoExistingCases() {
        ImpactResult impact = new ImpactResult(Set.of(), Set.of(), 0, Set.of(), Set.of());

        String prompt = promptBuilder.buildBusinessTestCaseUserPrompt(
                List.of(), List.of(), impact, List.of(), "", README);

        assertTrue(prompt.contains("## Project overview (README)"), prompt);
        assertTrue(prompt.contains("draft -> confirmed -> delivered"), prompt);
    }

    @Test
    void noReadmeMeansNoReadmeSection() {
        ImpactResult impact = new ImpactResult(Set.of(), Set.of(), 0, Set.of(), Set.of());

        String business = promptBuilder.buildBusinessTestCaseUserPrompt(
                List.of(), List.of(), impact, List.of(), "", "");
        String automation = promptBuilder.buildApiAutomationUserPrompt(
                List.of(), List.of(), List.of(), "", "", "");

        assertFalse(business.contains("Project overview (README)"), business);
        assertFalse(automation.contains("Project overview (README)"), automation);
    }

    @Test
    void existingSixArgCallsAreUnaffected() {
        // The pre-README overloads still work and add no README section.
        ImpactResult impact = new ImpactResult(Set.of(), Set.of(), 0, Set.of(), Set.of());
        String business = promptBuilder.buildBusinessTestCaseUserPrompt(
                List.of(), List.of(), impact, List.of(), "some related source");
        assertFalse(business.contains("Project overview (README)"), business);
    }
}
