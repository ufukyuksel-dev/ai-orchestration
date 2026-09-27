package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;

public record CodeFlowContext(
        CodeFlowSeed seed,
        List<CodeFlowContextSymbol> symbols,
        List<CodeFlowContextEdge> edges,
        List<String> services,
        List<String> externalClients,
        List<String> repositories,
        List<String> cacheKeys,
        List<String> requestModels,
        List<String> responseModels,
        List<CodeFlowSnippet> snippets,
        boolean truncated,
        List<String> warnings) {

    public CodeFlowContext {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        edges = edges == null ? List.of() : List.copyOf(edges);
        services = services == null ? List.of() : List.copyOf(services);
        externalClients = externalClients == null ? List.of() : List.copyOf(externalClients);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        cacheKeys = cacheKeys == null ? List.of() : List.copyOf(cacheKeys);
        requestModels = requestModels == null ? List.of() : List.copyOf(requestModels);
        responseModels = responseModels == null ? List.of() : List.copyOf(responseModels);
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
