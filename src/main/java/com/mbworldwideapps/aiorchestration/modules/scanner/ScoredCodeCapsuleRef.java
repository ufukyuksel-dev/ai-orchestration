package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

public record ScoredCodeCapsuleRef(UUID capsuleId, double similarityScore) {
}
