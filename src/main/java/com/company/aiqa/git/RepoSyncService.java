package com.company.aiqa.git;

import com.company.aiqa.config.CredentialScheme;
import com.company.aiqa.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Keeps a local, up-to-date working copy of a remote (possibly private) repo
 * so that GitDiffService - which operates on a local path - can run against
 * repos it doesn't already have on disk.
 *
 * Provider-agnostic: works against GitHub, GitLab (gitlab.com or
 * self-hosted), and generic HTTP(S) git servers (including ones reached by
 * bare IP, like an internal instance) with no code changes - only
 * configuration (see GitProperties / aiqa.git.*).
 */
@Service
public class RepoSyncService {

    private static final Logger log = LoggerFactory.getLogger(RepoSyncService.class);

    private final GitProperties gitProperties;

    public RepoSyncService(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    /**
     * Ensures a local clone of cloneUrl exists and is up to date, then returns
     * its absolute local path (suitable for GenerateTestsRequest.repoPath).
     * Uses server-configured credentials for whichever host cloneUrl points at.
     */
    public String syncRepo(String cloneUrl) {
        return syncRepo(cloneUrl, null, null);
    }

    /** Same as syncRepo(cloneUrl), but with a per-call token override. */
    public String syncRepo(String cloneUrl, String overrideToken) {
        return syncRepo(cloneUrl, overrideToken, null);
    }

    /**
     * Same as syncRepo(cloneUrl), but overrideToken - if non-blank - is used
     * for this call instead of the server-configured token, and
     * overrideScheme - if non-null - forces how that token is presented
     * (see CredentialScheme) instead of relying on auto-detection. This is
     * how a caller supplies their own credential per-request (e.g. via the
     * generate-tests-from-branch endpoint or a provider webhook) rather than
     * relying on a token baked into server config.
     */
    public synchronized String syncRepo(String cloneUrl, String overrideToken, CredentialScheme overrideScheme) {
        Path localPath = Path.of(gitProperties.getWorkspaceDir(), keyFor(cloneUrl));
        CredentialsProvider credentials = credentialsProvider(cloneUrl, overrideToken, overrideScheme);

        try {
            if (Files.isDirectory(localPath.resolve(".git"))) {
                log.info("Fetching latest for {} into {}", cloneUrl, localPath);
                try (Git git = Git.open(localPath.toFile())) {
                    git.fetch()
                            .setRemote("origin")
                            .setCredentialsProvider(credentials)
                            .call();
                    // Fetch updates origin/* but leaves the local branch and
                    // working tree at the commit the clone was made on. A repo
                    // that added a file after the clone (a README, say) then has
                    // it in origin/main but NOT in the local HEAD tree, so a read
                    // at HEAD - and the checkout on disk - silently misses it.
                    // These clones are read-only mirrors this platform never
                    // commits into, so hard-syncing the checked-out branch to its
                    // upstream is safe and is what "keep an up-to-date working
                    // copy" is supposed to mean.
                    updateWorkingCopyToRemote(git);
                }
            } else {
                Files.createDirectories(localPath.getParent() == null ? localPath : localPath.getParent());
                log.info("Cloning {} into {}", cloneUrl, localPath);
                try (Git git = Git.cloneRepository()
                        .setURI(cloneUrl)
                        .setDirectory(localPath.toFile())
                        .setCredentialsProvider(credentials)
                        .call()) {
                    // clone leaves the repo checked out at the default branch; that's fine,
                    // GitDiffService resolves refs directly rather than relying on the checkout.
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sync repo " + cloneUrl + ": " + e.getMessage(), e);
        }

        return localPath.toAbsolutePath().toString();
    }

    /**
     * Fast-forwards the checked-out branch to its upstream after a fetch, so the
     * local tree and working copy match the remote - the difference between a
     * clone that merely HAS the latest objects and one that actually reflects
     * them. Hard reset rather than merge because these clones are read-only
     * mirrors the platform never commits into, so there is never local work to
     * preserve and a reset can't fail on divergence.
     *
     * <p>Best-effort: on a detached HEAD, or a branch with no {@code origin/}
     * counterpart, there is nothing to fast-forward to and the checkout is left
     * as-is. Reads still resolve explicit refs (origin/branch, a SHA) directly,
     * so this only affects reads that go through the local branch/working tree.
     */
    static void updateWorkingCopyToRemote(Git git) {
        try {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            if (branch == null || ObjectId.isId(branch)) {
                // Detached HEAD (branch reads back as a raw SHA) - no upstream to track.
                return;
            }
            String upstream = "origin/" + branch;
            ObjectId target = repo.resolve(upstream);
            if (target == null) {
                return;
            }
            if (target.equals(repo.resolve("HEAD"))) {
                return;
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(upstream).call();
            log.info("Updated local checkout of branch '{}' to {} ({}).", branch, upstream, target.name());
        } catch (Exception e) {
            // Never fail a sync over this: the objects are fetched, and reads
            // that use explicit refs work regardless of the checkout's position.
            log.warn("Could not fast-forward the working copy to its upstream ({}); "
                    + "reads that rely on local HEAD may be stale.", e.getMessage());
        }
    }

    private CredentialsProvider credentialsProvider(String cloneUrl, String overrideToken, CredentialScheme overrideScheme) {
        String host = hostOf(cloneUrl);
        GitProperties.HostCredential hostConfig = gitProperties.findHost(host);

        String token = firstNonBlank(
                overrideToken,
                hostConfig != null ? hostConfig.getToken() : null,
                gitProperties.getDefaultToken());

        if (token == null || token.isBlank()) {
            // Falls back to anonymous - fine for public repos, will fail fast on private ones
            // with a clear "authentication required" error from JGit.
            log.info("No credential configured for host '{}' - attempting anonymous access", host);
            return CredentialsProvider.getDefault();
        }

        CredentialScheme scheme = overrideScheme != null ? overrideScheme
                : hostConfig != null && hostConfig.getScheme() != null ? hostConfig.getScheme()
                : autoDetectScheme(host);

        return switch (scheme) {
            case TOKEN_AS_USERNAME ->
                    // GitHub-style: https://<token>@host/... - GitHub ignores the username
                    // entirely once a valid token is in the password/username slot; empty
                    // password is the documented convention.
                    new UsernamePasswordCredentialsProvider(token, "");
            case TOKEN_AS_PASSWORD ->
                    // GitLab (and most Basic-auth-in-front-of-git servers, including generic
                    // on-prem/IP-addressed ones) require a non-blank username and accept the
                    // token as the password. "oauth2" is GitLab's own convention for this.
                    new UsernamePasswordCredentialsProvider("oauth2", token);
            case ANONYMOUS -> CredentialsProvider.getDefault();
        };
    }

    /** Best-effort hostname/provider auto-detection when no explicit scheme is configured. */
    private CredentialScheme autoDetectScheme(String host) {
        if (host == null) {
            return gitProperties.getDefaultScheme();
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.contains("github")) {
            return CredentialScheme.TOKEN_AS_USERNAME;
        }
        if (lower.contains("gitlab")) {
            return CredentialScheme.TOKEN_AS_PASSWORD;
        }
        // Unknown / self-hosted / bare-IP host - fall back to the configured
        // default, which itself defaults to TOKEN_AS_PASSWORD since that
        // convention is accepted by GitHub, GitLab, and most other servers.
        return gitProperties.getDefaultScheme();
    }

    private String hostOf(String cloneUrl) {
        try {
            URI uri = new URI(cloneUrl);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String keyFor(String cloneUrl) {
        return cloneUrl
                .replaceAll("^https?://", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-zA-Z0-9]+", "_");
    }
}
