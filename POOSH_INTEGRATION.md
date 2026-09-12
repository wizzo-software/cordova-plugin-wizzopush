# WizzoPush × POOSH — Full-Stack Integration Guide

End-to-end recipe for wiring a new app (Cordova mobile **and** web) to POOSH push,
using the **autonomous token_ref model**. This is the exact, battle-tested setup used
by Media Radar — follow it step by step for any new project.

> **Mental model.** POOSH is the push backend. It owns the Firebase project credentials,
> the token registry, the rate-limit and the send queue. Your app never talks to FCM for
> *delivery* — it only obtains a token (from its own Firebase) and hands it to POOSH, then
> asks POOSH to send. There are two token paths:
>
> | Platform | Who registers the token with POOSH | How |
> |---|---|---|
> | **Mobile** (Cordova) | the **device**, autonomously | the WizzoPush plugin → `POST /push/register-token` → emits `token_ref` → app saves it on the user |
> | **Web / desktop** | the **server** | browser sends raw FCM token → your server → `POST /push/register-token` → stores `token_ref` |
>
> In both cases the per-user handle you persist is the POOSH **`token_ref`** — a STRING
> like `tk_AbC123...`. There is **no numeric id** anywhere in POOSH responses.

---

## Prerequisites (one-time, per app)

1. **A Firebase project** for the app (Android `google-services.json`, iOS
   `GoogleService-Info.plist`, web config + VAPID key). The POOSH tenant MUST use the
   **same Firebase project** so it can deliver to the app's tokens.
2. **A POOSH tenant + API key** (`x-api-key`). This is the tenant key for the whole app.
3. The plugin: `cordova-plugin-wizzopush` (see [INSTALLATION.md](INSTALLATION.md) for the
   base Firebase/plugin install — do that first).

---

## Step 0 — Configure the `firebase_push` channel in the POOSH tenant ⚠️ REQUIRED

**Without this, POOSH cannot deliver and every send returns HTTP 412**
(`Firebase push channel is not configured for this tenant`). This is independent of the
app code — it's a property of the tenant.

1. Firebase Console → your project → ⚙️ Project Settings → **Service accounts** →
   **Generate new private key**. Downloads a JSON with `project_id`, `client_email`,
   `private_key`.
2. Upload it to POOSH (set `$POOSH_API_KEY` to the tenant key first — never paste it inline):

   ```bash
   curl -s -X POST https://api.poosh.work/channels \
     -H "Content-Type: application/json" \
     -H "x-api-key: $POOSH_API_KEY" \
     -d "$(node -e '
       const fs=require("fs");
       const sa=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
       process.stdout.write(JSON.stringify({
         channel_type: "firebase_push",
         is_active: true,
         credentials: { service_account_json: sa }
       }));
     ' ./service-account.json)"
   ```
3. Verify: `curl -s https://api.poosh.work/channels -H "x-api-key: $POOSH_API_KEY"`
   should list an active `firebase_push` channel.

> The service-account JSON is a **secret** — do NOT commit it. `firebase_push` credentials
> are shared by the `push_app` (native) and `push_web` (browser) sub-channels — you only
> need this ONE channel for both mobile and web.

---

## Part A — Mobile (Cordova), autonomous

### A1. Enable POOSH mode + subscribe to the token_ref

The plugin registers the device directly and emits a `token_ref`. Your only job is to save
that ref on the logged-in user. Do this once at startup (after the push service is created).

```javascript
// Enable device-side POOSH mode. OFF by default — this is the one-line switch.
WizzoPush.configurePoosh({
    enabled: true,
    apiKey: 'YOUR_POOSH_API_KEY'   // the tenant key
    // baseUrl defaults to https://api.poosh.work
});

// Fired whenever the device obtains/refreshes a token_ref from POOSH.
// Late-subscriber safe: fires immediately if a ref already exists.
WizzoPush.onPooshTokenRef(function (info) {
    // info = { tokenRef: 'tk_...', token: '<raw fcm>', platform: 'android'|'ios' }
    // POST it to your server to link the ref to the logged-in user:
    api('media/auth/set_push_token', {
        token_ref: info.tokenRef,
        token:     info.token,
        platform:  info.platform,
        type:      info.platform
    });
});
```

> **Do NOT also forward the raw mobile token to the server** for registration — that would
> double-register the device (two `tk_` rows + duplicate pushes). Mobile is fully autonomous;
> the server only *stores* the ref the device reports.

> **Do NOT wait for the `token_ref` in your enable flow.** Turning push on in the app is
> `hasPermission → grantPermission → configurePoosh` — each plugin call wrapped in a promise
> with a deadline — and nothing else. The plugin registers the device by itself (retrying until
> the FCM token exists) and reports through `onPooshTokenRef`, which fires **synchronously inside
> the subscribe call** when a ref is already known. Listener errors are isolated, so a throwing
> listener fails silently and the ref never reaches your server. Every "await the ref with settle
> flags and timers" implementation written so far hung at least once (a spinner that can neither
> resolve nor time out). Keep the listener trivial; after editing it run
> `eslint --no-eslintrc --env browser --rule no-undef:error` — bundlers ship an undefined
> identifier without a word.

### A2. Key gotcha — keep the API key OUT of the web bundle

The tenant key must never ship in the browser. If you build web and mobile from one codebase,
gate it on the build target:

```javascript
pooshApiKey: (import.meta.env.VITE_TARGET_PLATFORM &&
              import.meta.env.VITE_TARGET_PLATFORM !== 'web')
    ? (import.meta.env.VITE_POOSH_API_KEY || '')
    : '',   // empty on web → device POOSH mode stays off in the browser
```

---

## Part B — Web / desktop

Two ways to wire web push. **Option 1 (POOSH JS SDK) is preferred** — it makes web
symmetric with mobile: the browser registers directly with POOSH and hands the app a
`token_ref`, exactly like the mobile plugin's `onPooshTokenRef`. Option 2 (your own
Firebase SDK + server registration) is what older apps did and is still fully supported.

### B1. Option 1 — POOSH JS SDK with manual trigger (preferred)

Load the SDK and drive the opt-in from your own UI (no built-in popup). The SDK loads the
tenant's Firebase config from `/sdk/config`, registers its own service worker, and on
`requestToken()` returns a `token_ref`.

```html
<script type="module">
  import PooshSDK from 'https://api.poosh.work/sdk/poosh.js';

  // autoPrompt:false → suppress the built-in prompt; your app decides when to ask.
  const poosh = new PooshSDK('YOUR_POOSH_API_KEY', { autoPrompt: false });

  // When YOUR UI decides the user opted in (a button, a settings toggle, etc.):
  poosh.requestToken((info) => {
      // info = { tokenRef: 'tk_...', token: '<raw fcm>', platform: 'web' }
      api('media/auth/set_push_token', {
          token_ref: info.tokenRef, platform: 'web', type: 'web'
      });
  });
  // Or subscribe without triggering: poosh.onToken(cb) — fires on register + refresh.
</script>
```

This is the **same shape as mobile** (Part A): device/browser → POOSH → `token_ref` → app
saves it. The server just stores the ref (Part C2 mobile branch handles it). No server-side
`registerToken()` needed, and the raw token never has to reach your backend.

> The tenant API key is in the page — that's by design for this SDK (it's a device-facing
> tenant key). `autoPrompt:false` is the only flag you need to take over the UX; everything
> else (Firebase config, VAPID, service worker, data-only background notification) is handled
> by the SDK.

### B2. Option 2 — your own Firebase SDK + server registration

If the app already has its own Firebase web setup (like RADAR did originally), keep it:

- Use the Firebase JS SDK (`firebase/messaging`) with your web config + **VAPID key**.
- Register a service worker (`public/firebase-messaging-sw.js`) that draws the notification
  itself in the background:

  ```javascript
  messaging.onBackgroundMessage(function (payload) {
      // POOSH sends DATA-ONLY to web, so read from payload.data:
      const title = payload.data?.title || 'New Notification';
      const body  = payload.data?.body  || '';
      return self.registration.showNotification(title, {
          body,
          icon: '/img/icon-192.png',
          data: { ...payload.data }
      });
  });
  ```
- On `getToken`, forward the **raw** token to your server, which registers it with POOSH
  (Part C2 web branch):

  ```javascript
  api('media/auth/set_push_token', { token, platform: 'web', type: 'web' });
  ```

> **Why data-only for web (both options)?** The service worker draws the notification. If
> POOSH also sent a `notification` block, the browser would show a DUPLICATE. (This is the
> opposite of native — see the channelType note in Part C.)

---

## Part C — Server

The server stores the `token_ref` on the user and sends per-user pushes. Reference impl:
`media_push_service.js` + `media_auth_api.js` + `media_push_admin_api.js`.

### C1. Schema — one string column

```sql
ALTER TABLE users
  ADD COLUMN poosh_token_id  VARCHAR(64) NULL,   -- the tk_... STRING (NOT numeric)
  ADD COLUMN push_token_type VARCHAR(16) NULL,   -- 'web' | 'android' | 'ios'
  ADD COLUMN push_token_at   DATETIME    NULL;
ALTER TABLE users ADD KEY k_poosh_token_id (poosh_token_id);
```

### C2. `set_push_token` — mobile stores ref directly, web registers

```javascript
// MOBILE: the body already carries a tk_ ref → store as-is, no POOSH call.
const ref = body.token_ref;
if (typeof ref === 'string' && ref.startsWith('tk_')) {
    pooshTokenRef = ref;
}
// WEB/desktop: register the raw FCM token with POOSH, store the returned ref.
else if (platform === 'web' && pushService.isConfigured()) {
    const result = await pushService.registerToken({ fcmToken: token, platform });
    if (result?.token_ref?.startsWith('tk_')) pooshTokenRef = result.token_ref;
}
// Persist pooshTokenRef on users.poosh_token_id (+ type + timestamp).
```

> Accept **only** `tk_` refs. POOSH never returns a numeric id; a non-`tk_` value would be
> unsendable (it can't be resolved by `/messages`).

### C3. Sending

- **Per-user** → `POST /messages { token_refs:[...] }` (transactional, up to 100/call).
  Resolve the users' stored `tk_` refs, then send.
- **Topic / broadcast** → `POST /distributions { channels:['firebase_push'], ... }`.

⚠️ **Critical native gotcha — `/messages` must pass `channelType: 'push_app'` for native tokens.**
When the provider sends to an **android/ios** token, it MUST build a `notification` block
(via `channelType: 'push_app'`), or the OS shows **nothing while the app is backgrounded**
(FCM still returns `sent:1`, but it's a silent data-only message). Web tokens use
`firebase_push` (data-only — the SW draws it). The reference `media_push_service` /
POOSH `messages_service` already pick this per-token by platform:

```javascript
const channelType = (platform === 'android' || platform === 'ios')
    ? 'push_app'        // full notification block → shows in background
    : 'firebase_push';  // data-only → web SW draws it
```

### C4. Cleanup on logout / delete-account

- **Web logout / account delete:** server unregisters the raw token
  (`POST /push/unregister-token { token }`) and NULLs the push columns.
- **Mobile:** the device deactivates its own token (`configurePoosh({enabled:false})` →
  `unregisterToken`). The server does NOT unregister for mobile.

---

## Verification checklist (end-to-end)

1. **Tenant channel** — `GET /channels` shows active `firebase_push`. (else: 412 on every send)
2. **Mobile register** — open the app → `GET /push/tokens` shows a new `platform:android`
   token with a `tk_` ref → confirm it's saved on the user.
3. **Web register** — open the site, grant permission → a `platform:web` token appears.
4. **Per-user send (background!)** — `POST /messages { token_ref:'tk_...' }`:
   - native: notification appears with the app backgrounded (proves `push_app` channelType)
   - web: notification appears (proves the SW draws data-only)
5. **Click** — tapping reports back (`/t/<code>/click` on mobile; `notificationclick` on web).

---

## Common failures & causes

| Symptom | Cause |
|---|---|
| Every send returns **412** | Tenant has no `firebase_push` channel → do Step 0. |
| `sent:1` but **no notification on Android in background** | `/messages` sent data-only → pass `channelType:'push_app'` for native (C3). |
| **Duplicate** notification on web | A `notification` block was sent to web → web must be data-only; SW draws it (B1). |
| `sent:0, failed:1` to a real device | The token is stale/unregistered in FCM, or wrong Firebase project on the tenant. |
| Device registers **twice** (two `tk_` rows) | App forwarded the raw mobile token to the server in addition to `onPooshTokenRef` — don't (A1). |
| Web bundle leaks the API key | `pooshApiKey` not gated to mobile builds (A2). |
| **Switch spins forever** after Allow, no ref on the app server | (a) A **vendored/stale copy** of this plugin without POOSH mode — `configurePoosh`/`onPooshTokenRef` are silent no-ops; depend on `github:wizzo-software/cordova-plugin-wizzopush`. (b) Permission requested via the Activity instead of `cordova.requestPermissions` (fixed in PR #2). (c) The app's own JS awaited the ref and threw after marking itself settled — see the A1 callout. |
| Ref on the tenant, **never on the app server** | The `onPooshTokenRef` listener throws (e.g. calls a helper that a refactor deleted) — the plugin isolates listener errors, so nothing surfaces. Run `eslint no-undef`; read the live minified bundle: a function name surviving in full is an undefined global. |
| A device that **just received a push** gets retired by the app server | `/messages` `results` lists only the devices POOSH addressed; inactive ones are `skipped_inactive` and absent. Retire by absence only when entries carry `token_ref`, log one line per push. |
| Web: Allow clicked, switch stays off, `requestToken()` returns nothing | No `/poosh-sw.js` at the site root. Serve `importScripts("https://poosh.wizzo.media/sdk/poosh-sw.js")` with `Service-Worker-Allowed: /`. |

---

## POOSH device-facing API (quick reference)

All calls carry `x-api-key: <tenant key>`. Base host: `https://api.poosh.work`.

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/push/register-token` | `{ token, platform, topics? }` | `{ success, token_ref }` |
| POST | `/push/unregister-token` | `{ token }` | `{ success }` |
| PUT  | `/push/update-topics` | `{ token, topics }` | `{ success }` |
| POST | `/messages` | `{ token_refs[], title, body, url?, image_url?, data? }` | `{ sent, failed, results[] }` |
| POST | `/distributions` | `{ title, content, channels:['firebase_push'], target_topics?, send_now }` | distribution object |
| POST | `/channels` | `{ channel_type, is_active, credentials }` | `{ success }` |
| POST | `/t/:code/click` | `{}` (no auth) | `{ ok:true }` |
