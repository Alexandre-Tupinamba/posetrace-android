# Dependency matrix — Technical Spike

This spike deliberately targets `compileSdk = 36` with Android Gradle Plugin 8.13.2.
The dependency set is pinned to versions that do not require API 37 / AGP 9.x.

| Component | Version | Reason |
|---|---:|---|
| Android Gradle Plugin | 8.13.2 | Stable toolchain used by the spike |
| Gradle | 8.13 | Required baseline for AGP 8.13 |
| Kotlin | 2.4.21 | Supported by AGP 8.13.2; Compose compiler plugin uses same version |
| compileSdk | 36 | Stable Android 16 SDK target for this spike |
| targetSdk | 36 | Runtime behavior target |
| Compose BOM | 2025.12.00 | Compose 1.10 generation; before Compose 1.12's API 37/AGP 9 requirement |
| AndroidX Core KTX | 1.17.0 | Last Core line before compileSdk moved beyond API 36 |
| Activity Compose | 1.11.0 | Compiled with API 36 |
| Lifecycle | 2.9.4 | Stable 2025 line compatible with this toolchain |
| CameraX | 1.5.1 | Stable; conservative transitive AndroidX dependencies |
| MediaPipe Tasks Vision | 1.2.2 | Pinned MediaPipe Tasks artifact |

Do not upgrade one dependency in isolation. Re-check AAR metadata requirements first.
