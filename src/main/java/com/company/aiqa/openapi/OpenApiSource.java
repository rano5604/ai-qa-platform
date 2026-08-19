package com.company.aiqa.openapi;

/**
 * One place an OpenAPI document might be found, and the reasoning that put it
 * there.
 *
 * <p>A repository is not one service. mms is nine Maven modules behind a single
 * deployable, but the next repo is as likely to be four Spring Boot apps in one
 * tree, each with its own port, context path and document. So a location is
 * always attributed to a <b>component</b> - the module that owns the
 * {@code application.yml} it came from - and several are expected.
 *
 * <p>{@code why} exists because a spec that fails to load is a dead end the
 * next person has to re-derive from scratch. Saying "mms-app: springdoc
 * api-docs.path from mms-app/src/main/resources/application.yml" turns a
 * silent miss into a one-line fix.
 */
public record OpenApiSource(

        /** Module that owns it - "mms-app", or the repo name for a single-module tree. */
        String component,

        /** Absolute URL to fetch, or null when this is a file already in the repo. */
        String url,

        /** Repo-relative path to a checked-in spec, or null when this is a URL. */
        String filePath,

        /** How this location was arrived at, for the run summary. */
        String why
) {

    public static OpenApiSource url(String component, String url, String why) {
        return new OpenApiSource(component, url, null, why);
    }

    public static OpenApiSource file(String component, String filePath, String why) {
        return new OpenApiSource(component, null, filePath, why);
    }

    public boolean isUrl() {
        return url != null && !url.isBlank();
    }

    /** What to show a reader: the URL, or the repo path for a checked-in spec. */
    public String describe() {
        return component + " -> " + (isUrl() ? url : filePath) + "  (" + why + ")";
    }
}
