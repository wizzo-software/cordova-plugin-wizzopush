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
- 💬 **Sender avatar and conversations (Android, 1.1.0)** — a data-only push is drawn by the
  plugin like a chat message: the sender's picture in a circle, the app icon small in the
  corner, one card per conversation that stacks its messages, and on Android 11+ a real
  conversation with its shortcut. See "Sender avatar notifications" below.
- 📊 **Firebase Analytics** — `logEvent`, `setUserId`, `setUserProperty`, `setScreenName`.
- 🔑 **Google Sign-In** (`authenticateUserWithGoogle`) and **Microsoft Sign-In via MSAL**
  (`authenticateUserWithMicrosoft`).
- 🛎️ **Notification channels** (Android), **badges** (iOS), **topics**.
- ♻️ **FirebaseX compatibility** — same `window.FirebasePlugin` API; plugins that extend
  `FirebasePluginMessageReceiver` (e.g. VoIP) work without changes.

The FCM token always comes from **your app's own Firebase config**
(`google-services.json` / `GoogleService-Info.plist`) baked in at build time.

---

## Sender avatar notifications (Android)

An FCM `notification` message is drawn by the Firebase SDK while the app is in the background,
and the SDK never shows the sender's picture, only the app icon. Since 1.1.0 a **data-only**
message that arrives while the app is not on screen is drawn by the plugin itself
(`WizzoPushNotifier.java`), the way WhatsApp draws a chat message. When the app IS on screen
nothing is drawn: the JS `onMessageReceived` callback gets the message (with
`shownNatively: true` when the plugin drew it as well) and the app shows its own in-app UI.

Send a data-only message (no `notification` block; `android.priority: "high"`) whose `data`
carries these string keys:

| key | what it does |
|---|---|
| `title`, `body` | the text. At least one is required, otherwise nothing is shown |
| `icon` | https URL of the sender's picture (PNG/JPEG, square, 256px is plenty). Circled, shown big; cached on the phone for 7 days per URL, so change the URL (`?v=`) when the picture changes |
| `sender_name` | who sent it, bold. Turns the card into a conversation (MessagingStyle): name + text, avatar as the Person |
| `sender_key` | a stable key of the sender (defaults to `sender_name`) |
| `conversation_id` | the thread. Messages with the same id append to one card (the last 25) and share one long-lived shortcut, which is what Android 11+ needs to place it in the Conversations section and show the avatar as the bubble |
| `recipient_name` | the reader's own name (the "me" of the thread); defaults to the app name |
| `image` | https URL of a big picture (BigPictureStyle), used when the push is not a conversation |
| `channel_id` | notification channel (default `default`, created if missing) |
| `notification_id` | explicit integer id; default: hash of `conversation_id`, else of the message id |
| `url`, anything else | untouched, passed as intent extras: the tap payload in JS is the whole data map |

The small icon is the app's `fcm_push_icon` drawable when it has one, then Firebase's
`default_notification_icon` meta-data, then the launcher icon; the accent colour comes from
`default_notification_color`. Custom `WizzoPushMessageReceiver`s still run first: a message
they handle never reaches the notifier. A data-only message without `title`/`body` (a silent
sync) is forwarded to JS and shows nothing, as before.

Tapping the card opens the app with the data as extras (`onMessageReceived` with
`tap: "background"`, or `getInitialPushPayload()` on a cold start). When the user opens the
conversation inside the app, dismiss its card:

```javascript
WizzoPush.clearConversation(conversationId);
```

iOS is unchanged: only Communication Notifications (a Notification Service Extension with an
`INSendMessageIntent`) can replace the app icon there, and that lives in the app, not in
this plugin. Keep sending iOS a normal `notification` message.

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

## Building without analytics or MSAL

The plugin compiles five SDKs into the app: firebase-messaging (the push itself),
firebase-auth and play-services-auth (Google sign-in), firebase-analytics and MSAL
(Microsoft sign-in). The last two are the ones an app often does not use, and they are not
free:

- **firebase-analytics** merges `com.google.android.gms.permission.AD_ID`,
  `ACCESS_ADSERVICES_AD_ID` and `ACCESS_ADSERVICES_ATTRIBUTION` into the manifest. In Google
  Play that means declaring an advertising id in Data safety and answering the Ads
  questionnaire, in an app that may well show no ads at all.
- **MSAL** brings kotlin-stdlib, coroutines, datastore, nimbus-jose-jwt, moshi, okio, gson
  and httpcore5 with it, plus `com.yubico.yubikit`, which adds `android.permission.NFC` and
  a `usb.host` feature to the manifest.

Nothing changes by default: leave your app alone and both are compiled in exactly as before.
An app that wants them out excludes them from its own runtime classpath, in
`platforms/android/app/build-extras.gradle` (ship it from `config.xml` with a
`<resource-file>` so it survives `cordova prepare`):

```gradle
configurations.configureEach { config ->
    if (config.name.toLowerCase().contains('runtimeclasspath')) {
        config.exclude group: 'com.google.firebase', module: 'firebase-analytics'
        config.exclude group: 'com.microsoft.identity.client', module: 'msal'
    }
}
```

Runtime classpath only, on purpose: the compile classpath keeps both, so
`WizzoPushAnalytics.java` and `WizzoPushMsal.java` still compile. They are the only files in
the plugin that name those SDKs, and the plugin loads them defensively, so at runtime their
absence turns into `analytics == null` (analytics actions return "not part of this build",
everything else is untouched) and an error from `authenticateUserWithMicrosoft` instead of a
crash. Keeping it that way is a rule, not a detail: any new reference to
`com.google.firebase.analytics` or `com.microsoft.identity` outside those two files brings
the crash back for every app that excluded them.

Measured on Kringl (`wizzo_agent`, cordova-android 15). Same commit, same plugin, one debug
APK built with the exclusion and one without, both from `clean`, so the delta is the two
SDKs and nothing else (the web payload was a placeholder in both):

| | with both | without both |
|---|---|---|
| APK | 10,138,929 B | 5,422,886 B |
| classes in the dex | 18,939 | 10,001 |
| `com.microsoft.identity` | 2,629 | 0 |
| `com.yubico` | 222 | 0 |
| `com.google.android.gms.measurement` | 416 | 0 |
| `com.nimbusds` / `com.squareup` | 564 / 100 | 0 / 0 |
| `com.google.firebase.analytics` | 36 | 5 |
| **`com.google.firebase.messaging`** | **114** | **114** |

4,716,043 bytes (46.5%) and 8,938 classes, with push untouched. The five analytics classes that stay
are the `com.google.firebase.analytics.connector` interfaces, which belong to
firebase-messaging rather than to firebase-analytics and bring no permissions with them.
These permissions leave the merged manifest: `AD_ID`, `ACCESS_ADSERVICES_AD_ID`,
`ACCESS_ADSERVICES_ATTRIBUTION`, `NFC` and `BIND_GET_INSTALL_REFERRER_SERVICE`.
