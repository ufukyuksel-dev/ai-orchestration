package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

public interface MemoryVectorIndex {

    void upsert(MemoryItem item);

    void delete(MemoryItem item);

    List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter);
}
