package com.company.aiqa.parser;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaSourcesTest {

    /** Java 14+; the construct that made MerchantServiceImpl unparseable. */
    private static final String SWITCH_EXPRESSION_SOURCE = """
            class Fees {
                int rate(String tier) {
                    return switch (tier) {
                        case "GOLD" -> 1;
                        case "SILVER" -> 2;
                        default -> 3;
                    };
                }
            }
            """;

    private static final String RECORD_AND_TEXT_BLOCK_SOURCE = """
            record Merchant(String id) {
                static final String NOTE = \"""
                        multi
                        line\""";
            }
            """;

    @Test
    void parsesModernSyntax() {
        assertEquals("Fees", JavaSources.parse(SWITCH_EXPRESSION_SOURCE).getType(0).getNameAsString());
        assertEquals("Merchant", JavaSources.parse(RECORD_AND_TEXT_BLOCK_SOURCE).getType(0).getNameAsString());
    }

    /**
     * The actual regression: StaticJavaParser holds its configuration in a
     * ThreadLocal, so a language level set once at class-load time applied to
     * exactly one thread. Every request thread parsed at the default level and
     * skipped these files.
     */
    @Test
    void parsesModernSyntaxOnAnotherThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CompilationUnit> parsed = pool.submit(() -> JavaSources.parse(SWITCH_EXPRESSION_SOURCE));
            assertEquals("Fees", parsed.get().getType(0).getNameAsString());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void reusesOneParserPerThreadAndLevel() {
        assertSame(JavaSources.parser(JavaSources.ANALYSIS_LEVEL), JavaSources.parser(JavaSources.ANALYSIS_LEVEL));
    }
}
