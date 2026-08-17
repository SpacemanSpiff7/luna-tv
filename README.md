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
   adb install luna-tv-1.0.X.apk
   ```
3. The app appears in your TV launcher. If using a sideload manager, it appears there too.

### Build from Source

```bash
git clone https://github.com/SpacemanSpiff7/luna-tv.git
cd luna-tv
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK platform 34. The APK is at `app/build/outputs/apk/debug/luna-tv-1.0.X.apk`.

### Release signing

CI signs every build with a debug keystore restored from the `DEBUG_KEYSTORE_B64` repository secret. Without it, each run generates a throwaway key, every release gets a different signature, and installing an update over an existing install fails with "App not installed".

To (re)create the secret:

```bash
keytool -genkeypair -v -keystore debug.keystore \
  -storetype jks -storepass android -keypass android \
  -alias AndroidDebugKey -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Android Debug,O=Android,C=US"

base64 -i debug.keystore | gh secret set DEBUG_KEYSTORE_B64 -R SpacemanSpiff7/luna-tv
```

Keep `debug.keystore` somewhere safe (do **not** commit it). If the key is ever lost or rotated, users must uninstall and reinstall once, since signatures won't match.

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

## Settings

Open the settings dialog with the **Menu key** on your TV remote, or by **holding Back / Xbox B** for about a second (outside of fullscreen video). From there you can:

- **Display mode** — switch the user-agent between Desktop Chrome (default), Samsung TV (Tizen), LG TV (webOS), and Mobile Chrome. Luna serves a different layout for each; if the default looks oversized or you get an "unsupported browser" warning, try the TV modes. The choice is saved and the page reloads immediately.
- **Check for updates** — see Updates below.

## Updates

The app checks GitHub Releases once a day and offers to download and install new versions. You can also check manually from the settings dialog.

- The first time you install an update, Android asks you to allow Luna TV to install apps ("Install unknown apps") — grant it once and it's remembered.
- **Updating from v1.0.10 or earlier:** releases before the persistent signing key have mismatched signatures, so the in-app update will fail with "App not installed". Uninstall the old version once, sideload the new APK, and sign in again. All updates after that install normally and keep your login.

## Known Limitations

- **Input lag on MediaTek TVs** — some TVs with MediaTek chipsets (common in budget models and some Sony/Philips sets) show noticeable controller-to-screen latency. This is an upstream Chromium/WebView video-decoder issue on those SoCs, not something the app can fix. If your TV is affected, Luna's native apps on a Fire TV Stick or the Luna app in Chrome on another device will perform better.

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
