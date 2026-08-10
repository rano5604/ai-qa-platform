package com.company.aiqa.model;

import java.util.List;

/**
 * A single top-level (or nested) class/interface parsed out of a changed file.
 */
public record ClassInfo(
        String qualifiedName,
        String simpleName,
        String packageName,
        String sourceFilePath,
        List<String> imports,
        List<MethodInfo> methods,
        List<String> referencedTypes,
        SourceLanguage language
) {
}
