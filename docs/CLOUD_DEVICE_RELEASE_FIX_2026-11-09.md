# Cloud photo device test timing fix

The device regressions were caused by the tests observing or invoking the frame callback before the coordinator's authentication coroutine completed. `askPhotoQuestion` obtains a token on the IO dispatcher, returns to the main dispatcher, and only then requests a camera frame. The auth-before-capture production behavior remains unchanged.

The device tests now synchronize Main-dispatcher cases with instrumentation idle synchronization and explicitly pump queued IO/Main dispatchers in queued cases until frame capture is requested. Each bitmap-injection case asserts that the capture callback exists first. The cancellation/disposal checks still cover cancellation before a frame arrives, cancellation/disposal before the processing coroutine body starts, and cancellation/disposal after that body queues work on IO; they retain real bitmap recycling, callback suppression, and zero transport assertions.

Every test coordinator is disposed and every test bitmap is recycled in teardown. No genuine production defect was established by this timing failure. The revised tests have not been run on a device by this worker; coordinator-owned device validation remains required.
