# Plan V2 Approval View

1 project / 1 target / 2 intents / 1 step / 0 coordination / 1 validation / 1 risk / 0 citations / 0 rule checks shown

- planHash: `da7ed13c8bd9f9fca43bb333c6d8b953978eae76846ef6019918aee31d70369a`
- rendererVersion: `plan-v2/renderer-v1`

## Canonical Plan

- citations: []
- confidence: 0.91
- coordination: []
- goal: "Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — Long goal — "
- intents:
  - item:
    - dependencies:
      - "com.acme.orders.OrderService"
    - description: "Delegate the use case"
    - intentId: "i-delegate"
    - kind: "DELEGATE\_TO\_SERVICE"
    - targetIds:
      - "t-controller"
  - item:
    - dependencies: []
    - description: "Expose POST /orders"
    - intentId: "i-http"
    - kind: "HTTP\_MAPPING"
    - targetIds:
      - "t-controller"
- planId: "11111111-1111-1111-1111-111111111111"
- projects:
  - item:
    - baseline:
      - dirtyStateHash: "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      - headCommit: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      - repositoryFingerprint: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      - scannerEvidenceStatus: "MISSING"
      - scannerRevision: null
    - projectKey: "ORDERS\_API"
    - projectRef: "orders-api"
- revision: 1
- risks:
  - item:
    - description: "The endpoint contract may drift"
    - mitigation: "Lock the contract with a test"
    - riskId: "r-contract"
    - severity: "LOW"
- schemaVersion: 2
- steps:
  - item:
    - description: "Create the endpoint and delegation"
    - intentIds:
      - "i-delegate"
      - "i-http"
    - stepId: "s-controller"
    - targetIds:
      - "t-controller"
- targets:
  - item:
    - annotationHints:
      - "org.springframework.web.bind.annotation.RestController"
    - artifactKind: "CODE"
    - confidence: 0.91
    - evidence:
      - "neighbor controller"
      - "package convention"
    - generated: false
    - intentIds:
      - "i-delegate"
      - "i-http"
    - language: "JAVA"
    - operation: "CREATE"
    - previousPath: null
    - projectRef: "orders-api"
    - proposedSymbol: "com.acme.orders.OrderController"
    - repoRelativePath: "src/main/java/com/acme/orders/OrderController.java"
    - roleHints:
      - "web.http-controller"
    - sourceSet: "MAIN"
    - targetId: "t-controller"
- validationPlan:
  - item:
    - description: "Verify mapping, validation, and delegation"
    - kind: "UNIT\_TEST"
    - targetIds:
      - "t-controller"
    - validationId: "v-controller"

## Rule Checks

- ruleChecks: []
