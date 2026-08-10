package com.company.aiqa.config;

/**
 * How a personal access token should be placed into HTTP Basic auth
 * when talking to a given git host. Different platforms expect the
 * token in different fields:
 *
 *   TOKEN_AS_USERNAME - token goes in the username, password is blank.
 *                        This is GitHub's documented convention
 *                        (https://<token>@github.com/...). GitHub
 *                        actually ignores the username value entirely
 *                        as long as a valid token is supplied, but this
 *                        scheme name matches how their docs present it.
 *
 *   TOKEN_AS_PASSWORD  - a fixed non-blank username ("oauth2") is sent
 *                        with the token as the password. This is
 *                        required by GitLab (which rejects a blank
 *                        username) and also works fine against GitHub,
 *                        Bitbucket, Gitea, and most other git servers
 *                        that do plain HTTP Basic auth in front of
 *                        smart-HTTP git - including self-hosted /
 *                        on-prem instances reached by bare IP.
 *
 *   ANONYMOUS          - no credentials sent; only works for repos that
 *                        allow unauthenticated read access.
 */
public enum CredentialScheme {
    TOKEN_AS_USERNAME,
    TOKEN_AS_PASSWORD,
    ANONYMOUS
}
