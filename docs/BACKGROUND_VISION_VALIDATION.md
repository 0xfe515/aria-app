# Background vision session validation

Aria starts `VisionForegroundService` only from a visible, admitted Home or vision-diagnostics screen after camera permission is granted. The service owns the CameraX lifecycle at `STARTED`, publishes an ongoing notification with an explicit Stop action, and is `START_NOT_STICKY`; it does not start from boot or attempt to recreate a session after process death. When the Activity leaves the foreground, the UI detaches its preview surface while the service continues image analysis, IMU sampling, and hazard updates. Returning Home reuses the process-scoped controller and attaches a fresh preview surface.

Camera use cases are unbound individually by their owner rather than through `ProcessCameraProvider.unbindAll()`. Home explicitly stops the foreground service before launching `DistanceActivity`, allowing its existing separate controller to acquire the camera without modifying the distance workflow. Permission loss and CameraX errors clear results, stop monitoring, and leave an explicit stopped/error state; a failed session is not presented as active. The notification Stop action persists a deliberate-stop latch, so returning Home cannot silently restart the camera; the inset offers a visible restart action.

## Device test

Run on a connected camera-capable device:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionBackgroundSessionTest'
```

`VisionBackgroundSessionTest` admits Home with granted test permissions, waits for actual analyzer timestamps, sends the Home key, verifies fresh frames continue while the app is backgrounded, returns to Aria, verifies the same controller remains active, and sends the service Stop action to assert cleanup. This automates the foreground/background lifecycle on the connected device; it is not a physical walking or hazard-detection accuracy test.

## Platform checks and limitations

The camera foreground service declares both `android:foregroundServiceType="camera"` and `FOREGROUND_SERVICE_CAMERA`, and uses the camera foreground-service type in `startForeground()`. Android's camera permission is while-in-use restricted: the service start must be initiated while the Activity is visible and `CAMERA` is granted. Android 13+ notification permission affects notification-drawer visibility; the foreground service still posts its ongoing notification and remains subject to Android's foreground-service UI.

Run this test separately on Android 12 and Android 13+ devices. In this execution, only a Pixel 3 running Android 12 is connected; Android 13+ behavior, a Galaxy/Exynos device, external map applications, long-duration thermal/battery behavior, and physical walking remain unverified. Official platform reference: [Foreground service types — camera](https://developer.android.com/develop/background-work/services/fgs/service-types#camera).
