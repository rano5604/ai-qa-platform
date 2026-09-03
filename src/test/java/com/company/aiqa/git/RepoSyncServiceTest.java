package com.company.aiqa.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fetch alone leaves the local branch and working tree at the clone's original
 * commit: a file added upstream after the clone (a README, exactly the case that
 * surfaced this) sits in origin/main but not in the local HEAD tree or on disk.
 * updateWorkingCopyToRemote is what makes "keep an up-to-date working copy" true.
 */
class RepoSyncServiceTest {

    @Test
    void bringsAStaleWorkingCopyUpToTheUpstreamIncludingNewFiles(@TempDir Path tmp) throws Exception {
        Path remote = tmp.resolve("remote");
        Path clone = tmp.resolve("clone");

        // Remote at commit A - no README yet.
        try (Git origin = Git.init().setDirectory(remote.toFile()).call()) {
            write(remote, "src/App.java", "class App {}");
            origin.add().addFilepattern(".").call();
            origin.commit().setMessage("A").setAuthor("t", "t@t").call();

            // Clone it (working tree at A).
            try (Git ignored = Git.cloneRepository()
                    .setURI(remote.toUri().toString())
                    .setDirectory(clone.toFile())
                    .call()) {
                // no-op: just materialise the clone
            }

            // Remote advances to commit B - adds the README.
            write(remote, "README.md", "# App\nArchitectural context.");
            origin.add().addFilepattern(".").call();
            origin.commit().setMessage("B: add readme").setAuthor("t", "t@t").call();
        }

        // Before: clone doesn't have the README, and a fetch alone won't add it.
        assertTrue(Files.notExists(clone.resolve("README.md")), "precondition: README not cloned yet");

        try (Git git = Git.open(clone.toFile())) {
            git.fetch().setRemote("origin").call();
            assertTrue(Files.notExists(clone.resolve("README.md")), "fetch alone must not update the working tree");

            RepoSyncService.updateWorkingCopyToRemote(git);

            // After: the working tree and HEAD reflect the upstream, README and all.
            assertTrue(Files.exists(clone.resolve("README.md")), "README should be present after sync");
            // Content compared line-ending-insensitively - git may normalise CRLF on checkout.
            assertTrue(Files.readString(clone.resolve("README.md")).contains("Architectural context."),
                    "README content should be the upstream one");
            String branch = git.getRepository().getBranch();
            assertEquals(git.getRepository().resolve("origin/" + branch),
                    git.getRepository().resolve("HEAD"));
        }
    }

    private static void write(Path repo, String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
