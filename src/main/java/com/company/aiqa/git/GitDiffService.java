package com.company.aiqa.git;

import com.company.aiqa.model.ChangedFile;
import com.company.aiqa.model.CommitInfo;
import com.company.aiqa.model.ConfigType;
import com.company.aiqa.model.MergeCommitInfo;
import com.company.aiqa.model.SourceLanguage;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Step 1 of the pipeline: "Merge to Release Branch" -> "Git Diff".
 *
 * Given a local repository and two refs (typically the release branch before
 * and after a merge), returns every changed source file (across any
 * SourceLanguage this platform supports) together with its
 * unified diff and full post-change content.
 *
 * Merge detection strategy (see findLatestMergeCommit / findAllMergeCommits):
 * walks only the branch's FIRST-PARENT chain - commit -&gt; commit.getParent(0)
 * -&gt; ... - rather than filtering commits by shape (parentCount &gt; 1) or by
 * message content. On a protected branch where changes only land via merge
 * request, every step in that chain corresponds to exactly one MR landing,
 * regardless of merge method:
 *   - Merge commit / semi-linear:  the step IS the 2-parent merge commit
 *   - Fast-forward / Squash:       the step is a plain 1-parent commit
 * Both cases use the same pre-merge reference: the step's first parent, i.e.
 * the branch tip immediately before that change landed. Real merges are
 * detected identically to a naive parentCount&gt;1 filter; squash and
 * fast-forward merges are now detected too, with no dependency on commit
 * message wording (which varies by project/GitLab config and, in practice,
 * isn't reliably present at all).
 */
@Service
public class GitDiffService {

    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);

    /**
     * Sentinel baseRef meaning "diff against git's empty tree" - used for a
     * root/initial commit, which by definition has no parent to compare with.
     * Everything in that commit is then reported as ADDED, so a brand-new
     * repository's very first commit gets test cases like any other change
     * instead of being skipped for having nothing to diff against.
     */
    public static final String EMPTY_TREE_REF = "EMPTY_TREE";

    /**
     * Resolves a human-readable project name for repoPath, so generated test
     * cases can be organized under generated-tests/&lt;projectName&gt;/ instead
     * of one flat, shared output directory - important once this platform is
     * pointed at more than one repo, since a shared master CSV would
     * otherwise mix every project's test cases together.
     *
     * Reads the repo's own "origin" remote URL (set by RepoSyncService on
     * every clone/fetch, so this works for /generate-tests-from-branch and
     * the backfill/webhook paths without needing the URL threaded through as
     * a separate parameter) and takes its last path segment, minus ".git" -
     * e.g. "https://github.com/org/ai-qa-platform.git" -&gt; "ai-qa-platform".
     * Falls back to repoPath's own last path segment when there's no origin
     * remote configured (a bare local repo, or /generate-tests called
     * directly against an arbitrary local clone).
     */
    public String resolveProjectName(String repoPath) {
        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()) {
            String originUrl = repository.getConfig().getString("remote", "origin", "url");
            if (originUrl != null && !originUrl.isBlank()) {
                String fromUrl = lastPathSegmentSansGit(originUrl);
                if (fromUrl != null && !fromUrl.isBlank()) {
                    return sanitizeProjectName(fromUrl);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read 'origin' remote for {} - falling back to folder name: {}", repoPath, e.getMessage());
        }
        return sanitizeProjectName(lastPathSegmentSansGit(repoPath));
    }

    /** Strips a trailing "/" or "\" and ".git", then takes the final path segment. */
    private String lastPathSegmentSansGit(String pathOrUrl) {
        if (pathOrUrl == null) {
            return null;
        }
        String trimmed = pathOrUrl.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    /** Filesystem-safe folder name - same character class as sanitizeForPath in QaPipelineService. */
    private String sanitizeProjectName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown-project";
        }
        String safe = name.replaceAll("[^a-zA-Z0-9_.-]+", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /**
     * Computes the changed source files (any supported SourceLanguage) between baseRef and headRef.
     *
     * @param repoPath path to the working copy (the directory containing .git)
     * @param baseRef  ref before the change, e.g. "HEAD~1" or "origin/main"
     * @param headRef  ref after the change, e.g. "HEAD" or "origin/release/1.2"
     */
    public List<ChangedFile> computeChangedSourceFiles(String repoPath, String baseRef, String headRef) {
        List<ChangedFile> results = collectChangedFiles(repoPath, baseRef, headRef, SourceLanguage::isSupported);
        log.info("Found {} changed source file(s) between {} and {}", results.size(), baseRef, headRef);
        return results;
    }

    /**
     * Companion to {@link #computeChangedSourceFiles} that returns the changed
     * CONFIGURATION files (application.yml, *.properties, Dockerfile, pom.xml,
     * *.json/.xml/.env, etc. - see {@link ConfigType}) between the same two
     * refs, each with its unified diff and full post-change content.
     *
     * These are deliberately kept separate from source files: the source path
     * parses classes/methods and does dependency/impact analysis, none of which
     * applies to a config file. Config changes instead drive a dedicated
     * "config validation" test-case prompt (see QaPipelineService), so a merge
     * that only flips a feature flag or bumps a dependency still produces a QA
     * regression checklist instead of silently yielding nothing.
     */
    public List<ChangedFile> computeChangedConfigFiles(String repoPath, String baseRef, String headRef) {
        List<ChangedFile> results = collectChangedFiles(repoPath, baseRef, headRef, ConfigType::isConfigFile);
        log.info("Found {} changed config file(s) between {} and {}", results.size(), baseRef, headRef);
        return results;
    }

    /**
     * Shared diff walk: returns every changed file between baseRef and headRef
     * whose path satisfies {@code pathFilter}, with its per-file unified diff
     * and full post-change content. Both computeChangedSourceFiles and
     * computeChangedConfigFiles are just this with a different filter, so the
     * two views never drift apart in how they read the diff.
     */
    private List<ChangedFile> collectChangedFiles(String repoPath, String baseRef, String headRef,
                                                  Predicate<String> pathFilter) {
        List<ChangedFile> results = new ArrayList<>();

        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            // A root commit has no parent to diff against, so the caller passes
            // EMPTY_TREE_REF and we diff against git's empty tree instead -
            // every file in that commit then shows up as ADDED. Without this an
            // initial commit produced zero changed files and the whole repo's
            // first (often largest) body of code was silently skipped.
            boolean fromEmptyTree = EMPTY_TREE_REF.equals(baseRef);

            ObjectId baseId = fromEmptyTree ? null : repository.resolve(baseRef);
            ObjectId headId = repository.resolve(headRef);
            if ((!fromEmptyTree && baseId == null) || headId == null) {
                throw new IllegalArgumentException(
                        "Could not resolve refs. baseRef=%s -> %s, headRef=%s -> %s"
                                .formatted(baseRef, baseId, headRef, headId));
            }

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit headCommit = revWalk.parseCommit(headId);

                try (ObjectReader reader = repository.newObjectReader()) {
                    AbstractTreeIterator baseTree;
                    if (fromEmptyTree) {
                        baseTree = new EmptyTreeIterator();
                    } else {
                        CanonicalTreeParser parser = new CanonicalTreeParser();
                        parser.reset(reader, revWalk.parseCommit(baseId).getTree().getId());
                        baseTree = parser;
                    }

                    CanonicalTreeParser headTree = new CanonicalTreeParser();
                    headTree.reset(reader, headCommit.getTree().getId());

                    try (var git = new Git(repository);
                         DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {

                        formatter.setRepository(repository);
                        formatter.setDetectRenames(true);

                        List<DiffEntry> diffs = git.diff()
                                .setOldTree(baseTree)
                                .setNewTree(headTree)
                                .call();

                        for (DiffEntry entry : diffs) {
                            String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                                    ? entry.getOldPath()
                                    : entry.getNewPath();

                            if (path == null || !pathFilter.test(path)) {
                                continue;
                            }

                            ByteArrayOutputStream diffOut = new ByteArrayOutputStream();
                            try (DiffFormatter perEntryFormatter = new DiffFormatter(diffOut)) {
                                perEntryFormatter.setRepository(repository);
                                perEntryFormatter.format(entry);
                            }
                            String diffText = diffOut.toString(StandardCharsets.UTF_8);

                            String content = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                                    ? ""
                                    : readBlob(repository, headCommit, path);

                            results.add(new ChangedFile(
                                    path,
                                    mapChangeType(entry.getChangeType()),
                                    diffText,
                                    content
                            ));
                        }
                    }
                }
            }
        } catch (IOException | org.eclipse.jgit.api.errors.GitAPIException e) {
            log.error("Failed to compute git diff for repo {} ({} -> {})", repoPath, baseRef, headRef, e);
            throw new IllegalStateException("Git diff computation failed: " + e.getMessage(), e);
        }

        return results;
    }

    private String readBlob(Repository repository, RevCommit commit, String path) throws IOException {
        try (org.eclipse.jgit.treewalk.TreeWalk treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                repository, path, commit.getTree())) {
            if (treeWalk == null) {
                return "";
            }
            ObjectId blobId = treeWalk.getObjectId(0);
            byte[] bytes = repository.open(blobId).getBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private ChangedFile.ChangeType mapChangeType(DiffEntry.ChangeType jgitType) {
        return switch (jgitType) {
            case ADD -> ChangedFile.ChangeType.ADDED;
            case DELETE -> ChangedFile.ChangeType.DELETED;
            case RENAME -> ChangedFile.ChangeType.RENAMED;
            default -> ChangedFile.ChangeType.MODIFIED;
        };
    }

    /**
     * Returns the commit log between baseRef (exclusive) and headRef
     * (inclusive) - i.e. exactly the commits a "baseRef..headRef" git log
     * would show, newest first. For a merge found via findLatestMergeCommit,
     * pass its preMergeSha/mergeSha here to get the full list of commits
     * that merge actually brought in.
     */
    public List<CommitInfo> getCommitLog(String repoPath, String baseRef, String headRef) {
        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            boolean fromEmptyTree = EMPTY_TREE_REF.equals(baseRef);

            ObjectId baseId = fromEmptyTree ? null : repository.resolve(baseRef);
            ObjectId headId = repository.resolve(headRef);
            if ((!fromEmptyTree && baseId == null) || headId == null) {
                throw new IllegalArgumentException(
                        "Could not resolve refs for commit log. baseRef=%s -> %s, headRef=%s -> %s"
                                .formatted(baseRef, baseId, headRef, headId));
            }

            List<CommitInfo> commits = new ArrayList<>();
            try (Git git = new Git(repository)) {
                // No base to exclude for a root commit - everything reachable
                // from it IS the commit itself.
                Iterable<RevCommit> revCommits = fromEmptyTree
                        ? git.log().add(headId).call()
                        : git.log().addRange(baseId, headId).call();
                for (RevCommit commit : revCommits) {
                    PersonIdent author = commit.getAuthorIdent();
                    commits.add(new CommitInfo(
                            commit.getName(),
                            commit.getName().substring(0, Math.min(7, commit.getName().length())),
                            author.getName(),
                            author.getEmailAddress(),
                            Instant.ofEpochSecond(commit.getCommitTime()).toString(),
                            commit.getShortMessage()
                    ));
                }
            }

            log.info("Commit log {}..{}: {} commit(s)", baseRef, headRef, commits.size());
            return commits;
        } catch (IOException | org.eclipse.jgit.api.errors.GitAPIException e) {
            log.warn("Could not compute commit log for {}..{} in repo {}: {}",
                    baseRef, headRef, repoPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Finds the most recent merge commit reachable from the given branch and
     * returns it together with its first-parent commit (the branch's tip
     * immediately before that merge landed). This is exactly the before/after
     * pair to diff for "what did the last merge into this branch change".
     *
     * Accepts the branch name in any of these forms and tries them in order:
     * as given, "origin/&lt;branch&gt;", "refs/remotes/origin/&lt;branch&gt;".
     */
    public MergeCommitInfo findLatestMergeCommit(String repoPath, String branchName) {
        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            ObjectId branchId = resolveAnyOf(repository, branchName,
                    branchName,
                    "origin/" + branchName,
                    "refs/remotes/origin/" + branchName);

            if (branchId == null) {
                throw new IllegalArgumentException(
                        "Could not resolve branch '%s' (also tried origin/%s) in repo %s"
                                .formatted(branchName, branchName, repoPath));
            }

            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit tip = walk.parseCommit(branchId);
                if (tip.getParentCount() == 0) {
                    // Brand-new repository: the branch tip IS the initial
                    // commit. Diff it against the empty tree so its whole
                    // contents are treated as added, rather than refusing to
                    // process the repo at all.
                    log.info("Branch '{}' tip {} is the initial commit - diffing against the empty tree.",
                            branchName, tip.getName());
                    return new MergeCommitInfo(tip.getName(), EMPTY_TREE_REF);
                }
                RevCommit firstParent = tip.getParent(0);
                walk.parseHeaders(firstParent);
                log.info("Latest merge on '{}': {} (pre-merge: {}, {})",
                        branchName, tip.getName(), firstParent.getName(),
                        tip.getParentCount() > 1 ? "merge commit" : "squash/fast-forward");
                return new MergeCommitInfo(tip.getName(), firstParent.getName());
            }

        } catch (IOException e) {
            log.error("Failed to inspect repo {} for merges on branch {}", repoPath, branchName, e);
            throw new IllegalStateException("Repo inspection failed: " + e.getMessage(), e);
        }
    }

    private ObjectId resolveAnyOf(Repository repository, String label, String... candidates) throws IOException {
        for (String candidate : candidates) {
            ObjectId id = repository.resolve(candidate);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /**
     * Finds every merge-request landing reachable from the given branch
     * (real merge commits, squash merges, and fast-forward merges alike),
     * each paired with its first-parent pre-merge commit, returned OLDEST
     * first (the order a backfill should process them in, so dependency
     * graphs and impact analysis see the branch's history unfold the same
     * way it actually happened). Accepts the branch name the same way
     * findLatestMergeCommit does.
     */
    public List<MergeCommitInfo> findAllMergeCommits(String repoPath, String branchName) {
        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()) {

            ObjectId branchId = resolveAnyOf(repository, branchName,
                    branchName,
                    "origin/" + branchName,
                    "refs/remotes/origin/" + branchName);

            if (branchId == null) {
                throw new IllegalArgumentException(
                        "Could not resolve branch '%s' (also tried origin/%s) in repo %s"
                                .formatted(branchName, branchName, repoPath));
            }

            List<MergeCommitInfo> merges = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(branchId);
                while (commit.getParentCount() > 0) {
                    RevCommit firstParent = commit.getParent(0);
                    walk.parseHeaders(firstParent);
                    merges.add(new MergeCommitInfo(commit.getName(), firstParent.getName()));
                    commit = firstParent;
                }

                // The walk above stops AT the root commit without emitting it,
                // because it has no parent to pair with. Emit it against the
                // empty tree so the repository's initial import - frequently
                // the single largest body of code in the repo - is processed
                // too. Previously a single-commit repo yielded zero units of
                // work and reported "fully caught up" having done nothing.
                merges.add(new MergeCommitInfo(commit.getName(), EMPTY_TREE_REF));
            }

            // The walk above yields newest-first (same order findLatestMergeCommit
            // relies on to return the very first match) - reverse so callers get
            // chronological, oldest-first order for backfilling.
            java.util.Collections.reverse(merges);

            log.info("Found {} merge commit(s) reachable from branch '{}'", merges.size(), branchName);
            return merges;

        } catch (IOException e) {
            log.error("Failed to inspect repo {} for merges on branch {}", repoPath, branchName, e);
            throw new IllegalStateException("Repo inspection failed: " + e.getMessage(), e);
        }
    }

    /** Convenience overload for reading a changed file straight off disk (e.g. in CI checkouts). */
    public String readFileFromWorkingCopy(String repoPath, String relativePath) {
        try {
            return Files.readString(Path.of(repoPath, relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read working-copy file {}: {}", relativePath, e.getMessage());
            return "";
        }
    }
}
