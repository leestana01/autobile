# Security policy

Autobile runs as an Android accessibility service. Once a user grants that permission the
app can read the content of every screen they open and operate any app on their behalf.
That is what the product does, and it also means a vulnerability here is more serious
than in most applications. Please treat findings accordingly.

## Reporting a vulnerability

Report privately through
[GitHub security advisories](https://github.com/leestana01/autobile/security/advisories/new).
Do not open a public issue for anything that could be used against a running install.

Please include the affected version or commit, what an attacker would gain, and the
smallest reproduction you can manage. If a demonstration involves real screen content,
describe it rather than attaching it.

You should get an acknowledgement within a week. A fix timeline depends on severity, and
you will be credited in the advisory unless you ask otherwise.

## What is in scope

Anything that would let an attacker reach a user's screen content, their saved
automations, or the ability to act on their device. In particular:

- Making the agent perform an action the risk engine should have blocked or confirmed
- Causing screen content to leave the device when cloud access is off, or causing a
  screenshot to be sent when only text was consented to
- Defeating the redaction applied to execution history
- Reading or altering another app's saved automations
- Escalating a self-repair into a change of meaning without user confirmation
- Reaching an app the user has set to blocked or observe-only

## What is out of scope

- The Android accessibility permission itself. A user who grants it is granting broad
  access by design; the app explains this before requesting it.
- Behaviour of the third-party apps an automation operates.
- Inference quality. A model choosing the wrong on-screen element is a correctness bug,
  not a vulnerability, unless it bypasses a risk or policy control.
- Findings that require physical access to an unlocked device.

## Handling user data in a report

Demonstration traces, execution history and screenshots can contain anything that was on
the reporter's screen, including credentials and personal messages. Redact them before
sharing, and prefer a synthetic reproduction.
