# Default Naver destination follow-up — 2026-11-09

## Change

When a provider-free navigation utterance contains an explicit generic navigation phrase (for example, 길안내 or 길찾기) but has no valid destination, `extractPendingTarget` stores `nav_prov:naver`. It rejects conflicting providers, negated or compound requests, and provider-free speech without navigation intent; explicit provider follow-ups remain unchanged.

## Regression coverage

- Parser: `길안내해줘` asks for a destination and stores the Naver provider target.
- Parser: unrelated speech, bare destination, generic `안내해줘`, negated requests, and compound requests do not store a navigation target.
- Parser: a bare destination after the pending provider resolves to Naver before the 30-second expiry; after expiry it does not route.
- Coordinator: `길안내해줘` followed by `강남역` produces Naver external navigation without assigning a cloud question.

## Validation

- Scoped `git diff --check`: exit 0.
- Gradle build/tests/lint/androidTest compilation: not run by this worker; coordinator owns them. Earlier successful validation preceded this change and is not reused as acceptance evidence.
- Effective model: GPT-6 Luna medium (provided coordinator launch evidence).

## Next action

Coordinator independently reviews and runs the assigned Gradle validation. No device/install tests or commit/push performed.
