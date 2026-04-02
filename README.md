# Luna TV

Play Amazon Luna cloud games on your Android TV with an Xbox controller.

Amazon Luna works great in Chrome on Android but has no Android TV app. The Fire TV APK doesn't work on non-Amazon devices. Luna TV is a thin WebView wrapper that loads luna.amazon.com in fullscreen, presenting itself as a desktop Chrome browser so Luna serves the full desktop experience.

Tested on a Sony Bravia running Android TV 9.

## Requirements

- Android TV device running Android 9 (API 28) or later
- Xbox controller paired via Bluetooth
- Amazon Luna subscription
- Good internet connection (15+ Mbps recommended; use ethernet if possible)

## Install

### From Release

1. Download the latest APK from [Releases](../../releases/latest)
2. Transfer to your TV via USB drive, or install directly with ADB:
   ```bash
   adb install luna-tv-1.0.0-debug.apk
   ```
3. The app appears in your TV launcher. If using a sideload manager, it appears there too.

### Build from Source

```bash
git clone https://github.com/SpacemanSpiff7/luna-tv.git
cd luna-tv
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK platform 34. The APK is at `app/build/outputs/apk/debug/luna-tv-1.0.0-debug.apk`.

## First-Time Setup

1. **Pair your Xbox controller** to the TV via Bluetooth *before* launching the app
2. Launch Luna TV from the TV launcher
3. Sign in with your Amazon account (the login flow happens inside the app)
4. Your login persists across app restarts

## How It Works

The app loads luna.amazon.com in a fullscreen WebView with:

- Desktop Chrome user-agent (Luna rejects WebView and mobile UAs)
- Third-party cookies enabled (required for Amazon's cross-subdomain auth)
- Widevine DRM support (Luna streams are DRM-protected)
- HTML5 fullscreen video handling (required for game display)
- W3C Gamepad API (Xbox controller works natively over HTTPS in WebView)

The **Back button** (or Xbox B button) exits fullscreen first, then navigates back through Luna's pages, then exits the app. You won't accidentally close the app mid-game.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Luna says "unsupported browser" | Update Android System WebView from the Play Store (or Google Play Services) |
| Login redirects in a loop | Clear app data, restart the app, try again |
| Games won't start / black screen | Ensure your WebView supports Widevine DRM (`chrome://flags` in the TV's Chrome browser) |
| Controller not working | Pair the controller *before* launching the app. The Gamepad API requires the page to detect the controller on load |
| Laggy gameplay | Use wired ethernet instead of WiFi. Luna needs consistent low latency |
| Content cropped at edges | Check TV display settings for "overscan" or "display area" and set to "Full pixel" or disable overscan |
| App doesn't appear in TV launcher | Check that your TV launcher supports the leanback category. Use a sideload manager as a fallback |

## License

MIT
