# WizzoPush - Installation Guide

> Drop-in replacement for `cordova-plugin-firebasex` — Push Notifications, Firebase Analytics & Google Sign-In.

---

## Requirements

| | Minimum Version |
|---|---|
| Cordova CLI | 12+ |
| cordova-android | 14+ |
| cordova-ios | 7+ |

---

## Step 1 — Install the Plugin

```bash
cordova plugin add cordova-plugin-wizzopush
```

Or from a local path:

```bash
cordova plugin add ../cordova-plugin-wizzopush
```

---

## Step 2 — Firebase Configuration Files

Download configuration files from the [Firebase Console](https://console.firebase.google.com/) → Project Settings → Your Apps.

Place them in the **Cordova project root** (next to `config.xml`):

```
myapp/
├── config.xml
├── GoogleService-Info.plist   ← iOS (from Firebase Console)
├── google-services.json       ← Android (from Firebase Console)
├── www/
└── ...
```

> The plugin includes a hook script that automatically copies these files to the correct platform directories during `cordova platform add` and `cordova prepare`.

---

## Step 3 — Platform-Specific Setup

### Android

**No additional setup needed!** The plugin handles everything:
- ✅ FCM Service registration
- ✅ Google Services Gradle plugin
- ✅ Notification permissions
- ✅ Default notification channel

Just make sure `google-services.json` is in the project root.

### iOS

Add `GoogleService-Info.plist` as a **resource-file** inside `<platform name="ios">` in your `config.xml`:

```xml
<platform name="ios">
    <resource-file src="GoogleService-Info.plist" />
    
    <!-- ... your other iOS config ... -->
</platform>
```

> **Why?** This tells Cordova to register the file in the Xcode project (`.pbxproj`), not just copy it. Without this, Firebase can't find the file at runtime and the app will crash on launch.

#### Sender avatar notifications (iOS only — optional, 1.2.0)

Two preferences in `<platform name="ios">`, both off by default. The first adds the
`WizzoPushNSE` Notification Service Extension target to the Xcode project (bundle id
`<app id>.nse`, signed automatically); the second lets it hand iOS a Communication
Notification (the sender's face where the app icon sits), which needs the
**Communication Notifications** capability on the App ID in the Apple developer portal:

```xml
<platform name="ios">
    <preference name="WizzoPushNotificationServiceExtension" value="true" />
    <preference name="WizzoPushCommunicationNotifications" value="true" />
</platform>
```

Nothing else to do: `cordova prepare ios` runs the plugin's hook, which copies the
extension sources into `platforms/ios/WizzoPushNSE/`, adds the target and embeds it in the
app. Details and the payload keys: README, "Sender avatar notifications (iOS)".

#### Google Sign-In (iOS only — optional)

If you use Google Sign-In, add the **reversed client ID** as a URL scheme. You can find it inside `GoogleService-Info.plist` under `REVERSED_CLIENT_ID`:

```xml
<platform name="ios">
    <resource-file src="GoogleService-Info.plist" />
    
    <edit-config file="*-Info.plist" mode="merge" target="CFBundleURLTypes">
        <array>
            <dict>
                <key>CFBundleTypeRole</key>
                <string>Editor</string>
                <key>CFBundleURLSchemes</key>
                <array>
                    <string>com.googleusercontent.apps.YOUR_CLIENT_ID</string>
                </array>
            </dict>
        </array>
    </edit-config>
</platform>
```

Replace `YOUR_CLIENT_ID` with the value from your `GoogleService-Info.plist`.

---

## Step 4 — Build

```bash
# Android
cordova platform add android
cordova build android

# iOS
cordova platform add ios
cordova build ios
```

---

## Usage (JavaScript)

The plugin is available as `window.WizzoPush` **and** `window.FirebasePlugin` (for backward compatibility).

### Push Notifications

```javascript
const Push = window.WizzoPush;

// Request permission (iOS will show a prompt, Android 13+ will show a prompt)
Push.grantPermission(function(granted) {
    console.log('Permission granted:', granted);
});

// Get FCM token
Push.getToken(function(token) {
    console.log('FCM token:', token);
    // Send this token to your server
});

// Listen for token refresh
Push.onTokenRefresh(function(token) {
    console.log('Token refreshed:', token);
    // Update the token on your server
});

// Listen for incoming messages (foreground + taps)
Push.onMessageReceived(function(message) {
    console.log('Push received:', message);
    // message.tap === 'background' → user tapped a notification
    // message.tap is undefined → foreground message
});

// Get the payload that launched the app (cold start)
Push.getInitialPushPayload(function(payload) {
    if (payload) {
        console.log('App launched from push:', payload);
    }
});
```

### Topics

```javascript
Push.subscribe('news');
Push.unsubscribe('news');
```

### Badge (iOS)

```javascript
Push.setBadgeNumber(5);
Push.getBadgeNumber(function(count) {
    console.log('Badge:', count);
});
```

### Notification Channels (Android)

```javascript
Push.createChannel({
    id: 'my_channel',
    name: 'My Channel',
    description: 'My custom channel',
    importance: 4,        // 1-5
    visibility: 1,        // -1, 0, 1
    sound: 'default',
    vibration: true,
    light: true,
    lightColor: '#FF0000'
});

Push.deleteChannel('my_channel');
Push.listChannels(function(channels) {
    console.log(channels);
});
```

### Firebase Analytics

```javascript
Push.setAnalyticsCollectionEnabled(true);

Push.logEvent('purchase', { item: 'sword', price: 9.99 });

Push.setUserId('user_123');
Push.setUserProperty('favorite_color', 'blue');
Push.setScreenName('HomeScreen');
```

### Google Sign-In

```javascript
// webClientId = OAuth 2.0 Web Client ID from Google Cloud Console
Push.authenticateUserWithGoogle('YOUR_WEB_CLIENT_ID.apps.googleusercontent.com',
    function(credential) {
        console.log('Signed in:', credential.email);
        console.log('ID Token:', credential.idToken);
        // Send credential.idToken to your server for verification
    },
    function(error) {
        console.error('Sign-in error:', error);
    }
);

Push.signOutGoogle(function() {
    console.log('Signed out');
});
```

### Unregister (Delete Token)

```javascript
Push.unregister(function() {
    console.log('Token deleted');
});
```

---

## POOSH Backend Integration (optional)

WizzoPush can route the **entire push lifecycle through [POOSH](https://api.poosh.work)** —
token collection, token refresh, token deletion, topic subscriptions and notification-click
tracking — **directly from the device**, with no app backend required.

This is an **optional, instantly-reversible** add-on. It is **OFF by default**: with no
configuration, WizzoPush behaves exactly as a standard FirebaseX replacement and makes no
calls to POOSH.

### How it works

- The FCM token still comes from **your app's own Firebase config** (`google-services.json` /
  `GoogleService-Info.plist`). POOSH never replaces Firebase — it only *receives* the token.
- **Requirement:** the POOSH tenant (identified by your API key) must use the **same Firebase
  project** as the app, so POOSH can deliver to those tokens.
- When enabled, the following happen **automatically and transparently** (your existing code
  keeps working unchanged):
  - `getToken` → reports the token to POOSH (`POST /push/register-token`).
  - `onTokenRefresh` → re-registers the new token with POOSH.
  - `unregister` → deactivates the token in POOSH (`POST /push/unregister-token`), then deletes it from FCM.
  - `subscribe` / `unsubscribe` → keep FCM topics **and** sync POOSH topic targeting (`PUT /push/update-topics`).
  - a notification **tap** → reports the click to POOSH (`POST <tracking-url>/click`).
- Every POOSH call degrades gracefully: a POOSH/network failure **never breaks the app** —
  push still arrives via FCM regardless.

### Enable it

```javascript
// Turn POOSH on (persists across app restarts). Returns a config snapshot.
Push.configurePoosh({
    enabled: true,
    apiKey: 'YOUR_POOSH_API_KEY'
    // baseUrl: 'https://api.poosh.work'  // optional, this is the default
}, function(config) {
    console.log('POOSH enabled:', config);
});
```

That's it. From this point, tokens and clicks flow to POOSH with **no other code changes** —
the standard `getToken` / `onMessageReceived` / `subscribe` / `unregister` calls above now
also talk to POOSH.

### Trigger it from a button (enable / disable at runtime)

```javascript
// e.g. an in-app "Notifications ON" toggle
Push.configurePoosh({ enabled: true,  apiKey: 'YOUR_POOSH_API_KEY' });

// one-click revert to local-only (FirebaseX) behavior
Push.configurePoosh({ enabled: false });
```

### Linking the device to a user (token_ref callback)

When POOSH registers the device it returns a **`token_ref`** (`tk_...`) — a stable
reference to *this device's* push token. To target a specific user, the app links that
`token_ref` to the logged-in user and later sends with `POST /messages { token_ref }`.

The device does this **autonomously**: it registers directly with POOSH and emits the
`token_ref` back to the app via a callback. The app just subscribes and saves it on the
user — the server never registers/unregisters with POOSH itself.

```javascript
// Subscribe once (e.g. in main.js). Fires every time the device is (re-)registered,
// and immediately if a token_ref is already known — so subscribing late never misses it.
Push.onPooshTokenRef(function(info) {
    // info.tokenRef → the tk_... reference for this device
    // info.token    → the raw FCM token
    // info.platform → 'android' | 'ios'
    saveTokenRefOnUser(info.tokenRef);   // POST it to your own backend, stored on the user
});

// Equivalent DOM event (same payload under event.detail):
document.addEventListener('wizzopush.tokenref', function(e) {
    console.log('token_ref:', e.detail.tokenRef);
});
```

### Helpers

```javascript
Push.isPooshEnabled(function(on) { console.log('POOSH active:', on); });
Push.getPooshTokenRef(function(ref) { console.log('POOSH token ref:', ref); });
```

### Configuration

POOSH has **no plugin variables** — installing the plugin requires no POOSH config and adds no
required `--variable` flags. The app holds its own POOSH settings (API key, and optionally a
base URL) and passes them to `configurePoosh()` at runtime. The base URL defaults to
`https://api.poosh.work` inside the plugin, so typically only the API key is needed:

```javascript
Push.configurePoosh({ enabled: true, apiKey: 'YOUR_POOSH_API_KEY' });
```

---

## Migrating from FirebaseX

WizzoPush is a **drop-in replacement**. The plugin registers both `window.WizzoPush` and `window.FirebasePlugin`, so existing code that uses `window.FirebasePlugin` will work without changes.

### Steps:

1. Remove the old plugin:
   ```bash
   cordova plugin rm cordova-plugin-firebasex
   ```

2. Install WizzoPush:
   ```bash
   cordova plugin add cordova-plugin-wizzopush
   ```

3. Add `<resource-file src="GoogleService-Info.plist" />` in your iOS platform config (see Step 3 above).

4. Rebuild:
   ```bash
   cordova platform rm ios android
   cordova platform add ios android
   ```

5. **Done.** No JavaScript code changes needed.

### FirebaseX Compatibility

| FirebaseX Method | WizzoPush | Status |
|---|---|---|
| `getToken` | `getToken` | ✅ |
| `onTokenRefresh` | `onTokenRefresh` | ✅ |
| `onMessageReceived` | `onMessageReceived` | ✅ |
| `grantPermission` | `grantPermission` | ✅ |
| `hasPermission` | `hasPermission` | ✅ |
| `subscribe` / `unsubscribe` | `subscribe` / `unsubscribe` | ✅ |
| `setBadgeNumber` / `getBadgeNumber` | `setBadgeNumber` / `getBadgeNumber` | ✅ |
| `clearAllNotifications` | `clearAllNotifications` | ✅ |
| `createChannel` / `deleteChannel` | `createChannel` / `deleteChannel` | ✅ |
| `logEvent` | `logEvent` | ✅ |
| `setUserId` / `setUserProperty` | `setUserId` / `setUserProperty` | ✅ |
| `setScreenName` | `setScreenName` | ✅ |
| `authenticateUserWithGoogle` | `authenticateUserWithGoogle` | ✅ |
| `signOutGoogle` | `signOutGoogle` | ✅ |
| `deleteInstanceId` | `unregister` (+ alias) | ✅ |
| `getAPNSToken` | `getAPNSToken` | ✅ |
| `getInitialPushPayload` | `getInitialPushPayload` | ✅ (new) |
| `getDiagnostics` | `getDiagnostics` | ✅ (new) |
| — | `configurePoosh` / `isPooshEnabled` / `getPooshTokenRef` / `onPooshTokenRef` | ✅ (new — optional POOSH mode) |

### Message Receiver Compatibility (Android)

If you have other plugins that extend `FirebasePluginMessageReceiver` (e.g., VoIP call plugins), they will work automatically. WizzoPush includes compatibility classes that bridge `FirebasePluginMessageReceiver` → `WizzoPushMessageReceiver`.

---

## File Structure

```
cordova-plugin-wizzopush/
├── plugin.xml                          # Plugin definition
├── package.json
├── hooks/
│   └── copy_google_services.js         # Auto-copies Firebase config files
├── www/
│   ├── WizzoPush.js                    # JavaScript API
│   └── WizzoPushPoosh.js               # Optional POOSH backend client
└── src/
    ├── android/
    │   ├── WizzoPushPlugin.java        # Main Android plugin
    │   ├── WizzoFirebaseMessagingService.java  # FCM service
    │   ├── WizzoPushMessageReceiver.java       # Message receiver base
    │   ├── WizzoPushMessageReceiverManager.java
    │   ├── build.gradle                # Android dependencies
    │   └── compat/
    │       ├── FirebasePluginMessageReceiver.java      # FirebaseX compat
    │       └── FirebasePluginMessageReceiverManager.java
    └── ios/
        ├── WizzoPushPlugin.h / .m      # Main iOS plugin
        └── AppDelegate+WizzoPush.h / .m  # AppDelegate swizzling
```

---

## License

MIT
