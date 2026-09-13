# Architecture

Autobile compiles user demonstrations into provider-neutral semantic skills and executes
them through the least expensive runtime that can make a bounded decision. Application
code owns the state machine; no model receives control of the long-running agent loop.

## Execution path

```text
trigger or command
        │
        ▼
executability and risk policy
        │
        ▼
semantic skill step
        │
        ├── exact locator / accessibility semantics ── deterministic action
        │
        ├── on-device bounded inference ────────────── semantic action
        │
        └── consented cloud escalation ─────────────── semantic/visual action
                                                         │
                                                         ▼
                                          observed post-condition
                                                         │
                                          pass ──────────┴── fail
                                            │                 │
                                         next step       bounded recovery
```

Stable skills are expected to stay entirely on the deterministic path. A tier is selected
for one classification, match, extraction, validation, or recovery proposal—not for the
whole task.

## Modules

- `core` owns serializable skill IR, tasks, policy, metrics, and SQLite-backed storage. It
  contains no inference provider dependency.
- `ai` owns provider contracts, ML Kit Gemini Nano, optional local-engine seams, cloud
  transport, schema validation, routing, and context minimization.
- `runtime` owns accessibility perception and control, teaching, compilation, execution,
  validation, recovery, triggers, overlays, and background constraints.
- `app` assembles dependencies and provides the Compose interface.

Dependencies point inward: `app → runtime → ai → core`, with `runtime → core` directly for
domain types. Provider-specific types do not enter the skill format.

## Trust boundaries

Accessibility data may contain credentials, messages, financial data, or health data.
The following invariants apply:

1. Deterministic matching runs before inference.
2. Password-node text is excluded from immutable snapshots used by models and history.
3. Sensitive screenshot regions are irreversibly painted over before an image is routed.
4. Cloud inference and screenshot upload require separate opt-ins.
5. Cloud credentials are AES-GCM encrypted with a non-exportable Android Keystore key.
6. Cloud endpoints must use HTTPS.
7. Secure windows are never bypassed.
8. Risk policy can deny an action regardless of model output.
9. An unverifiable outcome is never reported as success.

## Persistence and compatibility

Skills are stored as complete JSON documents and archived by version before replacement.
New serialized fields require defaults so existing 0.1.x documents remain readable. The
`releaseTest` Android build applies production R8 rules while keeping the instrumentation
harness, allowing stored-skill round trips to be tested after shrinking.

## Background execution

Android does not provide a virtual background phone. A run may be deferred when the device
is locked, user confirmation is required, accessibility is disconnected, the network is
required but unavailable, or Android rejects a foreground-service start. Time triggers use
one-shot WorkManager jobs and are restored after boot; notification triggers structurally
filter locally before optional on-device semantic classification.

## Design constraints

- Android 11 / API 30 is the minimum because screen capture is required for visual fallback.
- Gemini Nano availability is probed at runtime and is never inferred from Android version.
- LiteRT-LM integration is not shipped in the current beta; `LocalInferenceEngine` is the
  provider seam for a future optional implementation.
- Google Play distribution is not assumed because accessibility policy may reject a general
  autonomous agent. GitHub Releases are the supported beta channel.
