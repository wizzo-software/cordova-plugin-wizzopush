# cordova-plugin-wizzopush

**A drop-in replacement for [`cordova-plugin-firebasex`](https://github.com/dpa99c/cordova-plugin-firebasex)** —
Push Notifications, Firebase Analytics, Google & Microsoft Sign-In — with an **optional,
instantly-reversible [POOSH](https://api.poosh.work) backend integration**.

The JavaScript API is 100% compatible with `FirebasePlugin`: the plugin registers both
`window.WizzoPush` **and** `window.FirebasePlugin`, so existing code keeps working unchanged.

```bash
cordova plugin add github:wizzo-software/cordova-plugin-wizzopush
```

> 📖 **Full setup, configuration files, and the complete API reference live in
> [INSTALLATION.md](INSTALLATION.md).**

---

## What it does

- 🔔 **Push notifications** — get / refresh / delete the FCM token, foreground messages,
  notification taps, cold-start payloads.
- 📊 **Firebase Analytics** — `logEvent`, `setUserId`, `setUserProperty`, `setScreenName`.
- 🔑 **Google Sign-In** (`authenticateUserWithGoogle`) and **Microsoft Sign-In via MSAL**
  (`authenticateUserWithMicrosoft`).
- 🛎️ **Notification channels** (Android), **badges** (iOS), **topics**.
- ♻️ **FirebaseX compatibility** — same `window.FirebasePlugin` API; plugins that extend
  `FirebasePluginMessageReceiver` (e.g. VoIP) work without changes.

The FCM token always comes from **your app's own Firebase config**
(`google-services.json` / `GoogleService-Info.plist`) baked in at build time.

---

## Optional: POOSH backend integration

WizzoPush can route the **entire push lifecycle through [POOSH](https://api.poosh.work)** —
token collection, refresh, deletion, topic subscriptions and notification-click tracking —
**directly from the device**, with **no app backend required**.

It is **OFF by default**: with no configuration, WizzoPush behaves exactly as a standard
FirebaseX replacement and makes **no** calls to POOSH. Turning it on (or off) is a single
runtime call — a true one-click switch.

```javascript
const Push = window.WizzoPush;

// Enable POOSH mode (persists across restarts). One-click revert: { enabled: false }.
Push.configurePoosh({ enabled: true, apiKey: 'YOUR_POOSH_API_KEY' });
```

When enabled, the following happen automatically and transparently — your existing
`getToken` / `onMessageReceived` / `subscribe` / `unregister` calls keep working unchanged:

| Event | POOSH call |
|---|---|
| `getToken` / `onTokenRefresh` | `POST /push/register-token` |
| `unregister` | `POST /push/unregister-token` (then deletes from FCM) |
| `subscribe` / `unsubscribe` | `PUT /push/update-topics` |
| notification **tap** | `POST <tracking-url>/click` |

Every POOSH call degrades gracefully — a POOSH/network failure **never breaks the app**;
push still arrives via FCM regardless.

### Linking a device to a user — autonomously

On registration POOSH returns a **`token_ref`** (`tk_...`) referencing *this device*. The
device emits it back via a callback; the app subscribes once and saves it on the logged-in
user. No server-side POOSH registration is involved — per-user sends then target
`POST /messages { token_ref }`.

```javascript
Push.onPooshTokenRef(function (info) {
    // info.tokenRef  → tk_... reference for this device
    // info.token     → raw FCM token
    // info.platform  → 'android' | 'ios'
    saveTokenRefOnUser(info.tokenRef);
});
```

> **Requirement:** the POOSH tenant (identified by the API key) must use the **same Firebase
> project** as the app, so POOSH can deliver to those tokens. POOSH never replaces Firebase —
> it only *receives* the token.

See the [POOSH Backend Integration](INSTALLATION.md#poosh-backend-integration-optional)
section of INSTALLATION.md for the full plugin-side API (`isPooshEnabled`, `getPooshTokenRef`,
`onPooshTokenRef`, the `wizzopush.tokenref` DOM event, and install-time variables).

> 🏗️ **Wiring a whole new app (mobile + web + server) to POOSH?**
> [**POOSH_INTEGRATION.md**](POOSH_INTEGRATION.md) is the full end-to-end recipe —
> tenant `firebase_push` channel setup, the autonomous mobile flow, the server-registered
> web flow, the `token_ref` schema, per-user vs broadcast sending, and the critical
> `channelType: 'push_app'` gotcha. Battle-tested on Media Radar.

---

## License

MIT © Wizzo Software
