package com.mbworldwideapps.aiorchestration.modules.scanner;

public class ScannerProjectRootMismatchException extends IllegalStateException {

    public ScannerProjectRootMismatchException(String projectKey, String requestedRoot, String boundRoot) {
        super("Project key '%s' is already bound to repository root '%s', not '%s'. "
                .formatted(projectKey, boundRoot, requestedRoot)
                + "Omit projectKey and use scanner.project.resolve(rootPath=<absolute root>), "
                + "or scan with the projectKey returned for that root.");
    }
}
