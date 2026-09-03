package com.company.aiqa.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/generate-tests-from-branch/check-new-commits.
 *
 * <p>Answers one question - "has this branch moved since it was last
 * backfilled?" - without generating anything. Clones/fetches repoUrl (reusing
 * an existing clone when there is one), walks the branch's merge history, and
 * reports how many of those merges are not yet recorded, without calling the
 * LLM for any of them. Meant as a cheap check before deciding whether a real
 * backfill is worth running.
 *
 * <p>Same fields as GenerateTestsFromBranchRequest's remote-access ones,
 * because the operation underneath - syncRepo - is identical: this genuinely
 * needs the network and, for a private repo, a credential, unlike
 * /merge-history and /merge-history/incomplete which only read local state.
 */
public class CheckNewCommitsRequest {

    @NotBlank
    private String repoUrl;

    @NotBlank
    private String branch;

    /**
     * Personal/project access token with read access to repoUrl. Optional if
     * the server already has a matching entry under aiqa.git.hosts (or
     * aiqa.git.default-token); never logged or persisted.
     */
    private String accessToken;

    /**
     * Optional hint for how accessToken should be sent: "GITHUB" (token as
     * username), "GITLAB"/"GENERIC" (token as password). Leave unset to
     * auto-detect from repoUrl's hostname.
     */
    private String provider;

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
