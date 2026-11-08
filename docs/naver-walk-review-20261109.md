# Naver walking route review follow-up

The unique Geocoder result continues to supply coordinates for route selection, while Aria now replaces its display name with the original spoken destination before building `dname` or invoking fallback search. This prevents Geocoder labels such as a street number or locality from discarding requests like “서울역 1번 출구.” Candidate selection also rejects out-of-range coordinates at its pure selection seam.

Geocoder callback completion uses a public cancellable continuation with an `AtomicBoolean` completion gate. Cancellation closes the gate, queued legacy work is cancelled and purged, and duplicate success/error callbacks cannot resume a completed continuation. The resolver no longer opts into `InternalCoroutinesApi`.

After the final launch call returns, MainScreen marks that request handled and consumes it only if it remains the current request. This avoids re-launching a route when the external map app immediately backgrounds Aria. Static whitespace validation passed; the coordinator owns the required Gradle validation and no real-device test was run.
