package com.company.aiqa.parser;

import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.SourceLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 2 of the pipeline: "Parse Changed Java Files" - generalized to
 * "Parse Changed Source Files" across every SourceLanguage the platform
 * supports.
 *
 * Java gets full AST-based parsing via JavaParserService (accurate method
 * signatures, call graphs, imports). Every other supported language goes
 * through GenericSourceParser's regex-based best-effort extraction. This
 * keeps Java's dependency-graph accuracy where a real parser dependency
 * already exists, while still giving every other language something
 * structured to hand the LLM, without a parser dependency per language.
 */
@Service
public class SourceParsingService {

    private static final Logger log = LoggerFactory.getLogger(SourceParsingService.class);

    private final JavaParserService javaParserService;
    private final GenericSourceParser genericSourceParser;

    public SourceParsingService(JavaParserService javaParserService, GenericSourceParser genericSourceParser) {
        this.javaParserService = javaParserService;
        this.genericSourceParser = genericSourceParser;
    }

    public List<ClassInfo> parseChangedFiles(List<ChangedFile> changedFiles) {
        List<ClassInfo> results = new ArrayList<>();

        List<ChangedFile> javaFiles = changedFiles.stream()
                .filter(f -> f.changeType() != ChangedFile.ChangeType.DELETED)
                .filter(f -> SourceLanguage.fromPath(f.path()).filter(l -> l == SourceLanguage.JAVA).isPresent())
                .toList();
        results.addAll(javaParserService.parseChangedFiles(javaFiles));

        for (ChangedFile file : changedFiles) {
            if (file.changeType() == ChangedFile.ChangeType.DELETED || file.fileContent().isBlank()) {
                continue;
            }
            var language = SourceLanguage.fromPath(file.path());
            if (language.isEmpty() || language.get() == SourceLanguage.JAVA) {
                continue; // Java already handled above; unsupported extensions shouldn't reach here anyway
            }
            try {
                results.addAll(genericSourceParser.parse(file, language.get()));
            } catch (Exception e) {
                log.warn("Failed to extract structure from {}: {}", file.path(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * Every class in the repo that exposes a REST endpoint, regardless of
     * whether it was touched by the change under analysis - see
     * JavaParserService.scanAllEndpoints for why automation needs the wider
     * view. Java-only, same scope as endpoint extraction itself.
     */
    public List<ClassInfo> scanAllEndpoints(com.company.aiqa.git.GitDiffService.SourceAtCommit source) {
        return javaParserService.scanAllEndpoints(source);
    }

    /**
     * The real field shape of the given payload types, read from the project's
     * own source - see JavaParserService.scanTypeSchemas. Java-only; other
     * languages get an empty list, and the automation prompt then falls back to
     * naming the type without its fields.
     */
    public List<com.company.aiqa.model.TypeSchema> scanTypeSchemas(
            com.company.aiqa.git.GitDiffService.SourceAtCommit source, java.util.Set<String> rootTypeNames,
            int maxDepth, int maxTypes) {
        return javaParserService.scanTypeSchemas(source, rootTypeNames, maxDepth, maxTypes);
    }
}
