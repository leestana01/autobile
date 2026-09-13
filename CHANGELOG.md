# Changelog

All notable changes to Autobile are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-09-13

### Security

- Encrypt cloud credentials with a non-exportable Android Keystore key and migrate the
  plaintext value written by 0.1.0 on first use.
- Treat accessibility password fields as sensitive, omit their values from screen
  descriptions and demonstration traces, and refuse to compile a recording containing
  secret input.
- Permanently mask password, payment, email, phone, and one-time-code regions in
  screenshots before they can reach an inference provider.
- Refuse cloud endpoints that do not use HTTPS and conceal cloud credentials in settings.

### Fixed

- Distinguish Android's secure-window screenshot refusal from internal, permission,
  invalid-window, display, and throttling failures.
- Stop a vision-dependent task with a blocked result when Android protects the current
  window instead of reporting a generic partial failure or attempting visual recovery.

### Quality

- Add regression coverage for protected-window execution, sensitive traces, pixel
  masking, encrypted credential storage, and HTTPS-only cloud configuration.
- Build the minified release variant in continuous integration on every pull request.

## [0.1.0] - 2026-09-11

### Added

- First public beta of the device-first accessibility runtime, semantic skill compiler,
  validation and recovery loop, triggers, risk controls, execution history, and Android UI.

[Unreleased]: https://github.com/leestana01/autobile/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/leestana01/autobile/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/leestana01/autobile/releases/tag/v0.1.0
