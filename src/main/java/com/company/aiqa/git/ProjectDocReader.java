package com.company.aiqa.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads a project's README from its own source at a commit, to feed the
 * generators as architectural and business-logic context.
 *
 * <p>The diff and the collaborator source say WHAT the code does; a README says
 * what it is FOR - which flows exist, which states matter, what a "valid" order
 * or an "approved" merchant means in the domain. That intent is exactly what a
 * good test prioritises and a good precondition respects, and it is nowhere in
 * the code. So when the target ships a README, it is worth as much to the
 * generators as the source is.
 *
 * <p>Read from the SAME commit as the code under test (via
 * {@link GitDiffService.SourceAtCommit}), never the working tree, so the doc
 * matches the revision being tested. Best-effort and bounded: the root README
 * wins over a nested one, the text is capped, and anything unreadable yields an
 * empty string - context is an enhancement, never a reason to fail generation.
 */
public final class ProjectDocReader {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocReader.class);

    /** Common README names, in any case. A bare "README" with no extension counts too. */
    private static final Pattern README_NAME =
            Pattern.compile("(?i)^readme(\\.(md|markdown|rst|adoc|txt))?$");

    private ProjectDocReader() {
    }

    /**
     * Whether a repo path names a README, by the same rule this reader uses to
     * pick one. Shared so README-change DETECTION (in the diff step) and README
     * READING never disagree on what counts as a README.
     */
    public static boolean isReadme(String path) {
        return path != null && README_NAME.matcher(fileName(path)).matches();
    }

    /**
     * @param source   the target's source at the commit under test; null yields ""
     * @param maxChars cap on the returned text; a longer README is truncated at
     *                 this many characters with a marker, so a huge doc can't
     *                 blow the prompt budget the fixed context already competes for
     * @return the README text (truncated), or "" when there is none or it can't be read
     */
    public static String readReadme(GitDiffService.SourceAtCommit source, int maxChars) {
        if (source == null) {
            return "";
        }
        String path = locateReadme(source.paths());
        if (path == null) {
            return "";
        }
        try {
            String body = source.read(path);
            if (body == null || body.isBlank()) {
                return "";
            }
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars)
                        + "\n\n... [README truncated at " + maxChars + " chars]";
            }
            log.info("Using {} as project architectural/business context ({} chars).", path, body.length());
            return body;
        } catch (RuntimeException e) {
            log.warn("Could not read README at {} ({}); generating without it.", path, e.getMessage());
            return "";
        }
    }

    /**
     * The README nearest the repo root wins: a top-level README describes the
     * whole project, while a nested one (a module's, a docs folder's) describes
     * only its corner. Ties break on the shortest path so the choice is stable.
     */
    private static String locateReadme(List<String> paths) {
        if (paths == null) {
            return null;
        }
        return paths.stream()
                .filter(ProjectDocReader::isReadme)
                .min(Comparator.comparingInt(ProjectDocReader::depth).thenComparingInt(String::length))
                .orElse(null);
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' || c == '\\') {
                depth++;
            }
        }
        return depth;
    }
}
