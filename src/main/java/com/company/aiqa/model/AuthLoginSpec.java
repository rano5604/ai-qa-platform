package com.company.aiqa.model;

import java.util.Map;

/**
 * How to obtain an auth token before a run, by logging in the way a real client
 * would.
 *
 * <p>Most targets don't take a static token - they take credentials at a login
 * endpoint and hand back a short-lived one ({@code POST /api/auth/signin} with
 * {@code {"username":"admin","password":"admin"}} → {@code {"token":"..."}}).
 * Supplying a raw {@code authHeaders} token means minting it by hand first and
 * pasting a value that expires; this lets the platform do the login itself, once,
 * at the start of the run, and inject the result into every request.
 *
 * <p>Only {@link #body} (the credentials) is truly required. The endpoint
 * {@link #url} and the {@link #tokenPath} the token sits at can be discovered
 * from the target's own source ({@code LoginEndpointScanner}) when omitted, so a
 * caller who doesn't know them supplies just the credentials. Everything else
 * has a conventional default.
 *
 * <p>Nothing here is project-specific: the same shape logs in to any service
 * that authenticates with a credentials POST returning a token in its body.
 */
public class AuthLoginSpec {

    /**
     * Login endpoint. Absolute ({@code http://host/api/auth/signin}) or a path
     * ({@code /api/auth/signin}) resolved against the run's baseUri. Optional:
     * discovered from the target source when blank.
     */
    private String url;

    /** HTTP method for the login call. Defaults to POST. */
    private String method;

    /** Credentials sent as the request body, serialized to JSON - e.g. {@code {"username":"admin","password":"admin"}}. */
    private Map<String, Object> body;

    /** Content type of the login request. Defaults to application/json. */
    private String contentType;

    /**
     * Dotted path to the token in the login response BODY - {@code "token"},
     * {@code "accessToken"}, {@code "data.token"}. Optional: when blank, a list
     * of common field names is tried, and source discovery can supply the exact
     * one.
     */
    private String tokenPath;

    /**
     * Response HEADER the token comes back in, when the service returns it there
     * rather than in the body - {@code "Authorization"} is the common one (a
     * Spring JWT filter often answers signin with {@code Authorization: Bearer
     * <token>} and a null token in the JSON). Optional: when blank, the body is
     * tried first and then common auth headers automatically, so a header-borne
     * token is usually found without setting this. A {@code "Bearer "} scheme
     * prefix is stripped before the token is re-wrapped by {@link #headerTemplate}.
     */
    private String tokenHeader;

    /** Header the token is sent under on every subsequent request. Defaults to Authorization. */
    private String headerName;

    /**
     * How the token is formatted into that header. {@code {token}} is replaced
     * with the extracted value. Defaults to {@code "Bearer {token}"}; use
     * {@code "{token}"} for an API that wants the bare token.
     */
    private String headerTemplate;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getTokenPath() { return tokenPath; }
    public void setTokenPath(String tokenPath) { this.tokenPath = tokenPath; }

    public String getTokenHeader() { return tokenHeader; }
    public void setTokenHeader(String tokenHeader) { this.tokenHeader = tokenHeader; }

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }

    public String getHeaderTemplate() { return headerTemplate; }
    public void setHeaderTemplate(String headerTemplate) { this.headerTemplate = headerTemplate; }
}
