## What this changes

<!-- The problem, and the approach. Explain why, not just what. -->

## How it was verified

<!-- Which commands you ran, and what you checked on a device if the change is device-facing. -->

- [ ] `./gradlew lintDebug test assembleDebug`
- [ ] `./gradlew connectedAndroidTest` (device-facing changes)
- [ ] `./gradlew connectedAndroidTest -PautobileTestBuildType=releaseTest` (serialised or reflective changes)

## Safety

<!-- Delete the lines that do not apply. -->

- [ ] Does not widen what leaves the device
- [ ] New actions carry validation, a failure path, and a risk classification
- [ ] Unsure cases fail safe rather than proceeding
