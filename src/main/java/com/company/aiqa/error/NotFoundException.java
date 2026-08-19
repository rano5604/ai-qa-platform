package com.company.aiqa.error;

/**
 * The request was well-formed, but what it asked for isn't there - a commit
 * with nothing generated for it yet, a script file that doesn't exist.
 *
 * <p>Exists because the controller previously answered every
 * IllegalArgumentException with 404, which made a caller mistake
 * ("supply repoPath or repoUrl") indistinguishable from a genuine miss and
 * told them the endpoint didn't exist. Splitting the two lets
 * IllegalArgumentException mean 400 - what it already meant everywhere it is
 * thrown - and leaves 404 for the cases that really are absent.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
