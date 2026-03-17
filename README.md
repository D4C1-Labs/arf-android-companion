# ARF Companion

Android companion app for ARF Flipper Zero firmware. Connects to Flipper over BLE and provides features that benefit from phone hardware.

**Status:** WIP — things may break.

## Features

### PSA Brute Force Accelerator
Offloads PSA TEA key brute-force from the Flipper to your phone. Uses native C (NDK) with multi-core threading to chew through 16M keys in seconds instead of minutes. 

- BF1 and BF2 key ranges
- Real-time progress and speed reporting
- Local benchmark mode
- Can be triggered from Flipper or manually from the app

### File Manager
Browse, upload, download, and manage files on the Flipper's SD card over BLE. Stock flipper feature. 

- Directory listing and navigation
- File upload (pick from phone storage) /broken/
- File download (save to phone)
- Create and delete folders/files 

## Building

Requires Android SDK and NDK 25.1.8937393 (auto-downloaded by AGP).

```bash
./gradlew assembleDebug
```

Or open in Android Studio and hit Run.

**Min SDK:** 26 (Android 8.0)

## License

See [LICENSE](LICENSE).
