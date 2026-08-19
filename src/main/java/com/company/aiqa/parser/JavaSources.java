package com.company.aiqa.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one place a Java source file is turned into an AST, at a language level
 * that actually matches the code this platform reads.
 *
 * <p>Why not {@code StaticJavaParser}: its configuration lives in a
 * <b>ThreadLocal</b> (see {@code StaticJavaParser.localConfiguration}), so
 * setting the language level once in a static initializer only ever configured
 * the thread that happened to load that class. Every Tomcat worker thread
 * handling a request got a fresh default configuration instead - language
 * level POPULAR, which is Java 11 - and any file using a switch expression
 * (Java 14+) failed to parse with "switch expressions are not supported".
 * Those classes were logged as a warning and silently never reached the
 * prompt, so the model reasoned about a feature with its main implementation
 * missing.
 *
 * <p>A JavaParser instance is not thread-safe either, so each thread keeps its
 * own per-language-level instance rather than sharing one field on a singleton
 * bean.
 */
public final class JavaSources {

    /**
     * BLEEDING_EDGE tracks the newest level the javaparser-core on the
     * classpath understands, so this keeps up with records, text blocks,
     * sealed types and switch patterns without hardcoding a version.
     */
    public static final ParserConfiguration.LanguageLevel ANALYSIS_LEVEL =
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE;

    private static final ThreadLocal<Map<ParserConfiguration.LanguageLevel, JavaParser>> PARSERS =
            ThreadLocal.withInitial(() -> new EnumMap<>(ParserConfiguration.LanguageLevel.class));

    private JavaSources() {
    }

    /** A parser for this thread at {@code level}; created on first use per thread. */
    public static JavaParser parser(ParserConfiguration.LanguageLevel level) {
        return PARSERS.get().computeIfAbsent(level,
                l -> new JavaParser(new ParserConfiguration().setLanguageLevel(l)));
    }

    /**
     * Parses source read from a repository under {@link #ANALYSIS_LEVEL}.
     *
     * @throws ParseProblemException with the syntax problems, matching what
     *         StaticJavaParser.parse threw - callers already log and skip the
     *         file on it.
     */
    public static CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> result = parser(ANALYSIS_LEVEL).parse(source);
        return result.getResult()
                .filter(cu -> result.isSuccessful())
                .orElseThrow(() -> new ParseProblemException(result.getProblems()));
    }
}
