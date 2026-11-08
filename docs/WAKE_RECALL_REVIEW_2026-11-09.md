# Wake recall independent review fixes

Corrected the matcher construction bug by generating compact literal forms such as `헤이아리아` first, escaping their actual characters, then inserting optional separators between characters. Canonical and mixed inputs (`헤이 아리아`, `해이아리야`, `Hey Aria`, `Hey 아리아`, `에이 알리아`) now share that construction; the prior observed `헤뤼이아`/`헤뤼이야` and related Korean spellings remain included.

N-best selection is explicitly rank-first among recognized wake candidates, bounded to `MAX_RECOGNITION_CANDIDATES`; a wake-like first candidate wins even if its trailing words differ from a lower-ranked alternative. Regressions cover canonical/observed variants, NFKC and decomposed Hangul, punctuation and compact command extraction, rank and candidate bounds, standalone full/short names, single-syllable rejection, and unrelated utterances.

Offline KWS provisioning now contains 22 Korean token sequences, including standalone `아리`/`알리`, prefixed `알리아`/`알리야`, and retained `헤뤼이아`/`헤뤼이야` spellings. All tokens were checked against the generated model `tokens.txt`. Added `WakeRecallNativeSpotterTest` to instantiate the bundled native spotter, create a stream, and process a synthetic silence chunk. Static token validation and `git diff --check` passed; Gradle, JVM, lint, and device execution remain the coordinator's responsibility.
