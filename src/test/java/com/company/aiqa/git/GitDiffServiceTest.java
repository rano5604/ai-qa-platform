package com.company.aiqa.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * findOriginUrlForProject is the reverse of resolveProjectName: given a
 * project name, find the local clone that produces it and hand back the raw
 * URL merge history's (repoUrl, branch) key is actually built from - the
 * missing piece for /merge-history and /merge-history/incomplete to accept
 * projectName instead of repoUrl.
 */
class GitDiffServiceTest {

    private final GitDiffService service = new GitDiffService();

    @Test
    void findsTheOriginUrlOfTheMatchingClone(@TempDir Path workspace) throws Exception {
        initCloneWithOrigin(workspace.resolve("mms"), "https://github.com/acquiring-system/mms.git");

        String url = service.findOriginUrlForProject(workspace.toString(), "mms");

        assertEquals("https://github.com/acquiring-system/mms.git", url);
    }

    /** resolveProjectName's own matching is case-insensitive - this has to agree with it. */
    @Test
    void matchingIsCaseInsensitive(@TempDir Path workspace) throws Exception {
        initCloneWithOrigin(workspace.resolve("QueueManagement"), "https://github.com/rano5604/QueueManagement.git");

        String url = service.findOriginUrlForProject(workspace.toString(), "queuemanagement");

        assertEquals("https://github.com/rano5604/QueueManagement.git", url);
    }

    @Test
    void returnsNullWhenNoCloneMatches(@TempDir Path workspace) throws Exception {
        initCloneWithOrigin(workspace.resolve("mms"), "https://github.com/acquiring-system/mms.git");

        assertNull(service.findOriginUrlForProject(workspace.toString(), "does-not-exist"));
    }

    @Test
    void returnsNullWhenTheWorkspaceDirDoesNotExist(@TempDir Path workspace) {
        assertNull(service.findOriginUrlForProject(workspace.resolve("never-created").toString(), "mms"));
    }

    /** A stray non-git folder under the workspace (a leftover, a clone mid-delete) must not blow up the scan. */
    @Test
    void skipsANonGitDirectoryInTheWorkspace(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("not-a-repo"));
        initCloneWithOrigin(workspace.resolve("mms"), "https://github.com/acquiring-system/mms.git");

        String url = service.findOriginUrlForProject(workspace.toString(), "mms");

        assertEquals("https://github.com/acquiring-system/mms.git", url);
    }

    @Test
    void returnsNullForABlankProjectName() {
        assertNull(service.findOriginUrlForProject("anything", ""));
        assertNull(service.findOriginUrlForProject("anything", null));
    }

    // --- computeChangedReadme: the third change signal, so a README-only
    // commit during backfill isn't skipped as "nothing changed". ---

    @Test
    void detectsAChangedReadme(@TempDir Path repo) throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            commit(git, repo, "src/App.java", "class App {}", "initial");
            commit(git, repo, "README.md", "# App\nNow does more.", "update readme");
        }
        var changed = service.computeChangedReadme(repo.toString(), "HEAD~1", "HEAD");

        assertEquals("README.md", changed.map(f -> f.path()).orElse(null));
    }

    @Test
    void reportsNoReadmeChangeWhenOnlySourceMoved(@TempDir Path repo) throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            commit(git, repo, "README.md", "# App", "seed readme");
            commit(git, repo, "src/App.java", "class App { int x; }", "source only");
        }
        assertEquals(java.util.Optional.empty(),
                service.computeChangedReadme(repo.toString(), "HEAD~1", "HEAD"));
    }

    @Test
    void prefersTheRootReadmeWhenSeveralChanged(@TempDir Path repo) throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            commit(git, repo, "src/App.java", "class App {}", "initial");
            git.add().addFilepattern(".").call();
            writeFile(repo, "README.md", "root");
            writeFile(repo, "module/README.md", "nested");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("two readmes").setAuthor("t", "t@t").call();
        }
        assertEquals("README.md",
                service.computeChangedReadme(repo.toString(), "HEAD~1", "HEAD").map(f -> f.path()).orElse(null));
    }

    private void commit(Git git, Path repo, String path, String content, String message) throws Exception {
        writeFile(repo, path, content);
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("t", "t@t").call();
    }

    private void writeFile(Path repo, String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent() == null ? repo : file.getParent());
        Files.writeString(file, content);
    }

    private void initCloneWithOrigin(Path dir, String originUrl) throws IOException {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", originUrl);
            config.save();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException(e);
        }
    }
}
