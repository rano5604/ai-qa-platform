package com.company.aiqa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the "aiqa.git" section of application.yml. This is the single,
 * provider-agnostic place credentials for cloning/fetching repos over
 * HTTPS are configured, whether the repo lives on github.com, gitlab.com,
 * a self-hosted GitLab/Gitea/Bitbucket instance, or a bare internal git
 * server reached by IP (e.g. http://10.88.230.18/...).
 *
 * Resolution order for a given clone URL (see RepoSyncService):
 *   1. A per-request override token (e.g. GenerateTestsFromBranchRequest.accessToken).
 *   2. The most specific matching entry in "hosts" for that URL's host[:port].
 *   3. "default-token", if set.
 *   4. Anonymous (works only for public repos).
 *
 * The auth scheme (where the token goes in the Basic auth handshake) is
 * resolved similarly: an explicit scheme on the matched host entry wins;
 * otherwise it's auto-detected from the hostname ("github" -> token as
 * username, "gitlab" -> token as password); otherwise "default-scheme"
 * applies, which defaults to TOKEN_AS_PASSWORD since that convention
 * (non-blank username + token password) is accepted by GitHub, GitLab,
 * and most other HTTP-Basic-in-front-of-git setups alike.
 */
@ConfigurationProperties(prefix = "aiqa.git")
public class GitProperties {

    /** Local directory where repos are cloned/kept up to date for server-side clone flows. */
    private String workspaceDir = "repo-workspace";

    /** Directory (relative to workspaceDir) where processed-merge history files are kept. */
    private String historyDir = "merge-history";

    /** Fallback token used when no host-specific entry matches and no per-request token is given. */
    private String defaultToken;

    /** Fallback auth scheme used when a host has no explicit scheme and hostname auto-detection doesn't match. */
    private CredentialScheme defaultScheme = CredentialScheme.TOKEN_AS_PASSWORD;

    /** Per-host credential/scheme overrides, e.g. github.com, gitlab.com, or an internal host/IP. */
    private List<HostCredential> hosts = new ArrayList<>();

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    public String getHistoryDir() { return historyDir; }
    public void setHistoryDir(String historyDir) { this.historyDir = historyDir; }

    public String getDefaultToken() { return defaultToken; }
    public void setDefaultToken(String defaultToken) { this.defaultToken = defaultToken; }

    public CredentialScheme getDefaultScheme() { return defaultScheme; }
    public void setDefaultScheme(CredentialScheme defaultScheme) { this.defaultScheme = defaultScheme; }

    public List<HostCredential> getHosts() { return hosts; }
    public void setHosts(List<HostCredential> hosts) { this.hosts = hosts; }

    /** Finds the config entry whose host matches (case-insensitively), if any. */
    public HostCredential findHost(String host) {
        if (host == null) {
            return null;
        }
        return hosts.stream()
                .filter(h -> h.getHost() != null && h.getHost().equalsIgnoreCase(host))
                .findFirst()
                .orElse(null);
    }

    /** One entry under aiqa.git.hosts, e.g.:
     *  <pre>
     *  aiqa:
     *    git:
     *      hosts:
     *        - host: github.com
     *          token: ${GITHUB_TOKEN:}
     *        - host: gitlab.com
     *          token: ${GITLAB_TOKEN:}
     *        - host: 10.88.230.18
     *          token: ${INTERNAL_GIT_TOKEN:}
     *          scheme: TOKEN_AS_PASSWORD
     *  </pre>
     */
    public static class HostCredential {
        /** Hostname or bare IP as it appears in the clone URL, e.g. "gitlab.com" or "10.88.230.18". Port, if any, should be included as host:port. */
        private String host;

        /** Personal/project access token with read access to repos on this host. */
        private String token;

        /** Explicit auth scheme for this host; leave unset to auto-detect from the hostname. */
        private CredentialScheme scheme;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public CredentialScheme getScheme() { return scheme; }
        public void setScheme(CredentialScheme scheme) { this.scheme = scheme; }
    }
}
