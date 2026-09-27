package com.mbworldwideapps.aiorchestration.modules.scanner;

public class ProjectWorkspaceResolutionException extends IllegalStateException {

    public ProjectWorkspaceResolutionException(String reason) {
        super(reason);
    }

    public ProjectWorkspaceResolutionException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
