# Wake session test repair (2026-11-09)

Updated `VoiceListeningSessionTest` to separate incomplete wake fragments from the now-valid standalone `아리아` and short `헤이 아리` wake forms. Incomplete fragments still leave the session waiting, produce no wake/input/command callbacks, and do not carry into the following session; that following session accepts standalone `아리아` and executes the suffix once. A separate test verifies a short valid phrase in partial text opens wake state without executing a command until final recognition, then checks exact callback/command counts across another short final.

Validation: `git diff --check` passed. Build, JVM tests, lint, and device validation remain coordinator-owned per task scope.
