# Luna TV

Android TV WebView wrapper for Amazon Luna cloud gaming. Targets Sony Bravia TVs with Xbox controllers over Bluetooth.

## Build

```bash
./gradlew assembleDebug
```

CI runs on GitHub Actions — push to `main` triggers build + APK artifact upload + GitHub Release.

No local Android SDK required; CI handles everything. If building locally, you need JDK 17 + Android SDK platform 34.

## Architecture

Single Activity (`MainActivity.java`), single layout. ~250 lines of Java. No fragments, no ViewModels.

### Why these choices matter

- **Desktop Chrome UA**: Luna rejects WebView UA (`wv` token) and serves unusable mobile layouts for mobile UAs
- **X-Requested-With removal**: second WebView detection vector; removed via `WebSettingsCompat`
- **Third-party cookies**: Amazon auth bounces across subdomains; without them, login loops
- **Widevine DRM**: `onPermissionRequest` auto-grants `PROTECTED_MEDIA_ID` for game streams
- **HTML5 fullscreen**: WebView ignores fullscreen requests by default; `onShowCustomView`/`onHideCustomView` swap views
- **Dual immersive mode**: `WindowInsetsController` (API 30+) and legacy flags (API 28-29)
- **Back = Xbox B**: exit fullscreen → goBack() → exit app
- **Fullscreen API spoof**: JS injection makes Luna think page is already fullscreen

### Gamepad

W3C Gamepad API works natively in WebView over HTTPS. No Java-side gamepad code needed.

## File Map

```
app/src/main/
├── AndroidManifest.xml          # TV features, permissions, config changes
├── java/com/lunatv/app/
│   └── MainActivity.java        # Everything: WebView, cookies, DRM, fullscreen, immersive
└── res/
    ├── layout/activity_main.xml # FrameLayout: WebView + error overlay
    ├── drawable/                 # TV banner, launcher icons
    └── values/                  # Theme (NoActionBar, black bg), strings, colors
```

## Dependencies

- `androidx.appcompat:appcompat:1.6.1`
- `androidx.webkit:webkit:1.8.0`
- `androidx.leanback:leanback:1.0.0`

compileSdk 34, minSdk 28, targetSdk 34. Java 11 source. AGP 8.2.2, Gradle 8.4.

## Tested On

Sony Bravia, Android TV 9, Xbox controller over Bluetooth.
