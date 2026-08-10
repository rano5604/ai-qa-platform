package com.company.aiqa.project;

import com.company.aiqa.model.ClassInfo;
import com.company.aiqa.model.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Answers "does this project have a UI, or is it a backend/API-only
 * service?" - used by PromptBuilder to decide whether a manual test case's
 * steps should walk through a UI (the default, tester-facing wording) or
 * call the REST endpoint directly with its HTTP method/path/payload (see
 * ApiEndpointInfo), since a project with no UI has no screen for a manual
 * tester to click through in the first place.
 *
 * This is a project-wide characteristic, not a per-diff one - a repo either
 * has UI code somewhere or it doesn't - so it scans the whole working copy
 * (like DependencyService already does for cross-file dependency edges),
 * not just the files touched by the current merge.
 */
@Service
public class ProjectShapeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProjectShapeAnalyzer.class);

    /** File extensions that are essentially always UI code when present. */
    private static final Set<String> UI_EXTENSIONS = Set.of(
            ".jsx", ".tsx", ".vue", ".storyboard", ".xib");

    /** Directories not worth walking - never contain project-authored UI, just noise/false positives. */
    private static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
            "node_modules", ".git", "target", "build", "dist", "out", ".idea", ".gradle", ".venv", "venv");

    private static final int MAX_FILES_SCANNED = 20_000;

    /**
     * True when the project exposes at least one REST endpoint (see
     * ApiEndpointInfo on MethodInfo, set by JavaParserService/GenericSourceParser)
     * AND the working copy shows no sign of UI code. A project with both API
     * endpoints and a UI (the common full-stack case) is NOT API-only - it
     * keeps the normal tester-facing wording, since there IS a UI to test
     * through even though the diff happens to touch a controller.
     */
    public boolean isApiOnly(String repoPath, List<ClassInfo> classes) {
        boolean hasEndpoint = classes.stream()
                .flatMap(c -> c.methods().stream())
                .map(MethodInfo::apiEndpoint)
                .anyMatch(e -> e != null);

        if (!hasEndpoint) {
            return false;
        }
        return !hasUiSignal(repoPath);
    }

    private boolean hasUiSignal(String repoPath) {
        Path root = Path.of(repoPath);
        if (!Files.isDirectory(root)) {
            return false;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .limit(MAX_FILES_SCANNED)
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .anyMatch(this::looksLikeUiFile);
        } catch (IOException e) {
            log.warn("Could not scan {} for UI markers - assuming a UI is present (safer default): {}",
                    repoPath, e.getMessage());
            return true;
        }
    }

    private boolean isExcluded(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (EXCLUDED_DIR_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeUiFile(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();

        if (UI_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
            return true;
        }
        if (name.equals("AndroidManifest.xml")) {
            return true;
        }
        if (lower.endsWith(".xml") && containsPathSegment(file, "layout")) {
            return true; // Android res/layout/*.xml
        }
        if (lower.equals("package.json") && hasUiFrameworkDependency(file)) {
            return true;
        }
        if (lower.endsWith(".dart") && looksLikeFlutterWidget(file)) {
            return true;
        }
        return false;
    }

    private boolean containsPathSegment(Path file, String segment) {
        for (Path part : file) {
            if (part.toString().equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUiFrameworkDependency(Path packageJson) {
        try {
            String content = Files.readString(packageJson);
            return content.contains("\"react\"") || content.contains("\"vue\"")
                    || content.contains("\"@angular/core\"") || content.contains("\"svelte\"")
                    || content.contains("\"next\"") || content.contains("\"nuxt\"");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean looksLikeFlutterWidget(Path dartFile) {
        try {
            String content = Files.readString(dartFile);
            return content.contains("StatelessWidget") || content.contains("StatefulWidget")
                    || content.contains("MaterialApp") || content.contains("runApp(");
        } catch (IOException e) {
            return false;
        }
    }
}
