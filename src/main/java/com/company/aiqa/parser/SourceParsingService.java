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
}
