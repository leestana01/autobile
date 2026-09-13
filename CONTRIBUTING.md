# Contributing

Thanks for considering a contribution. This document covers what you need to build the
project and the standards a change is held to.

## Getting set up

- Android SDK 36
- JDK 21 — Robolectric's Android 36 sandbox does not run on 17
- A device or emulator on Android 11 (API 30) or newer

Set `sdk.dir` in `local.properties` or export `ANDROID_HOME`, then:

```bash
./gradlew lintDebug test assembleDebug assembleRelease
```

That is the same command CI runs on every pull request.

## Running on a device

Autobile needs accessibility access to do anything. Install the app, open it, and follow
onboarding — it explains each permission before sending you to system settings. The
starter automation opens system settings and is safe to run repeatedly while you are
working on the execution loop.

## Testing

Unit tests cover the domain, routing and execution logic:

```bash
./gradlew test
```

Instrumentation tests cover startup and storage on a real device:

```bash
./gradlew connectedAndroidTest
```

Run them against the shrunk build before changing anything that is serialised or reached
reflectively:

```bash
./gradlew connectedAndroidTest -PautobileTestBuildType=releaseTest
```

Stored automations decode through generated serializers that R8 can remove while the
build still succeeds and every unit test still passes. That failure surfaces on a user's
phone after an update, as every automation they taught disappearing, so it is worth the
extra run.

## What a change needs

This app operates other people's phones, so the bar is higher than the code alone
suggests.

**Keep it provider-neutral and semantic.** Nothing in `core` may reference a model, a
prompt format or a vendor. Steps target meaning, not coordinates. A skill compiled on one
device has to run on another with entirely different capabilities.

**New actions need more than an implementation.** Validation that proves the action did
what it claimed, a failure path that does not report success, a risk classification, and
tests for both the supported and unsupported device states.

**Fail safe when you are unsure.** An unrecognised enum value should keep a step, not
delete it. An unverifiable outcome is a failure, not a pass. A repair that changes what an
automation means waits for the user.

**Do not widen what leaves the device.** Cloud access is opt-in, screenshots are a
separate opt-in again, and context minimisation runs before any tier is chosen. If a
change sends more than before, say so explicitly in the pull request.

**Preserve stored skills.** New serialized fields need defaults. Changes to model
hierarchies, serializers, or R8 rules must pass the `releaseTest` instrumentation suite.

**Explain why in comments, not what.** The code says what it does. Comments should cover
the reasoning that is not recoverable from reading it — why a threshold is that value, why
an order matters, what breaks if it is changed.

## Commits and pull requests

Keep commits focused and their subjects short. Pull request descriptions should explain
the problem, the approach, and anything a reviewer should be sceptical about. Mention test
coverage and how you verified device-facing behaviour.

## Never commit

Credentials, signing keys, learned user data, demonstration traces, screenshots of real
screens, local model files, or `local.properties`.
