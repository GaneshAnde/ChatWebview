# ChatWebView (Android)

A single-Activity Android app that loads the [chat-app](../chat-app) frontend
in a WebView, with its own html/css/js requests cached to disk so repeat and
offline loads don't need the network.

## Demo Video

The demo covers WebView navigation, Add Contact, native notifications,
deep linking, offline caching, and local persistence.

[▶ Watch the demo on Google Drive](https://drive.google.com/file/d/1ZY6EUkAPIzry84REVVo0WI3sOdvup6US/view?usp=sharing)

## How it fits together

- The chat frontend (`chat-app/`) is a **single-page app** — the list, thread,
  and add-contact screens are all client-side JS state inside one loaded
  `index.html` (see `chat-list.js`/`chat-detail.js`/`chat-new.js`). There's no
  separate URL per screen.
- So Android's job is small: load `index.html` once, and make sure its
  html/css/js requests get cached. Everything else — navigating to a chat,
  opening the add-contact form — is the JS you already built, running as-is
  inside the WebView.

## Files

- `MainActivity.kt` — creates the WebView, points it at `BASE_URL + "/index.html"`,
  installs `CachingWebViewClient` and `ChatNativeBridge`, owns the nav drawer,
  back-button handling, and the notification deep-link flow
- `CachingWebViewClient.kt` — the interception logic: on `shouldInterceptRequest`,
  checks `FileCache` first; on a miss, fetches over `HttpURLConnection`, caches
  the response, and serves it. Only requests under `BASE_URL` ending in
  `.html`/`.css`/`.js` are intercepted — everything else passes through to
  WebView's normal handling untouched.
- `FileCache.kt` — a minimal disk cache keyed by SHA-256 of the URL (two files
  per entry: bytes + content type). No eviction — fine for a handful of
  app-shell files; not meant to grow past that.
- `ChatNativeBridge.kt` — the JS-to-Android half of the bridge: a single
  `@JavascriptInterface` method (`onScreenChanged`) called from
  `chat-detail.js`/`chat-new.js`, letting native track which screen — and,
  for the thread, which chat — is currently visible.
- `NotificationHelper.kt` — notification channel setup and the "new message"
  notification builder, with a `PendingIntent` that deep-links back into the
  right chat on tap.

## JS↔Android bridge

Bidirectional, and each direction has a real purpose — not built just to
exist:

- **Android → JS (fixes the back button):** `MainActivity` never overrides
  back by guessing what screen is showing. Instead it calls
  `window.AndroidNav.back()` (defined in the frontend's `android-bridge.js`)
  and reads the boolean result: `true` means a screen was closed in the page
  (thread or add-contact → list), so Android does nothing further; `false`
  means the list was already showing, so Android falls through to its normal
  back behavior (exiting the Activity). Hardware back now correctly steps
  thread/add-contact → list → exit. (If the drawer is open, back closes that
  first instead — see "Nav drawer" below.)
- **JS → Android (drives the drawer and notification suppression):**
  `chat-detail.js` and `chat-new.js` call
  `window.AndroidBridge.onScreenChanged(screen, chatId)` — `screen` is
  `"list"`, `"thread"`, or `"newchat"` — every time the visible screen
  changes. `MainActivity` tracks this as `currentScreen` /
  `currentlyOpenChatId` and uses it for two things: showing/hiding the nav
  drawer trigger (list-screen-only), and skipping a simulated notification
  when it's for the chat you're already looking at.
- **Android → JS (deep link):** no new bridge method needed here — tapping a
  notification just calls the already-public `window.ChatDetail.open(chatId)`.

`@JavascriptInterface` methods run on a WebView-internal thread, not the UI
thread, so `ChatNativeBridge` posts back via `runOnUiThread` before touching
`currentScreen`/`currentlyOpenChatId`.

## Nav drawer

A left drawer on the list screen only, with two items:

- **Messages** — closes the drawer; no navigation happens, since you're
  already on the list. It's shown highlighted (accent background, bold accent
  text) every time the drawer opens — there's currently only one section, and
  the drawer is only reachable from the list screen in the first place (see
  below), so "Messages" is definitionally always the active one. If you add a
  second drawer destination later, this highlight needs to become conditional
  rather than hardcoded — see `setUpDrawer()` in `MainActivity.kt`.
- **Simulate notification** — triggers the same `simulateIncomingMessage()`
  flow described below, then closes the drawer.

**"No left navigation on the detail screen"** is enforced two ways, not just
visually: `updateDrawerAvailability()` sets the hamburger trigger to
`View.GONE` (not just invisible) and locks the drawer itself via
`DrawerLayout.LOCK_MODE_LOCKED_CLOSED` whenever `currentScreen != "list"` —
so it can't be opened by an edge swipe either, not only by a hidden button.


## Push notifications (simulated)

The drawer's **"Simulate notification" item** posts a notification for a
random demo contact and, on tap, launches/resumes the app with that chat
open.

**This is not real push.** There's no backend here, no FCM integration, no
server that ever tells the app a message arrived — the button exists because
there's nothing else to trigger the pipeline with. A real integration would
receive a push payload (chat id, sender, preview) via Firebase Cloud
Messaging and call `NotificationHelper.showMessageNotification(...)` from
that handler instead of a button tap; everything downstream of that call
(channel setup, the `PendingIntent`, deep-linking into the right chat once
the WebView loads) is the same either way.

The demo contact list in `MainActivity.DEMO_CONTACTS` is a hardcoded mirror
of a few entries from `chat-app/data.js`, for the same reason — a real
payload would carry this data itself, not require Android to already know it.

Notifications need `POST_NOTIFICATIONS` permission on API 33+; the app
requests it at launch (declining it just means the simulate button won't
visibly do anything — no crash, but check the permission if nothing appears).

## Run it

1. Start the chat frontend's local server first (from the `chat-app` project):
   ```bash
   cd chat-app
   python3 -m http.server 8000
   ```
2. Open this folder in Android Studio, let it sync, and run on an emulator.
   `MainActivity.BASE_URL` already points at `http://10.0.2.2:8000`, which is
   the emulator's alias for your host machine's `localhost` — no change
   needed for emulator use.
3. **Physical device instead?** Change `BASE_URL` in `MainActivity.kt` to your
   dev machine's LAN IP (`ipconfig getifaddr en0` on macOS), e.g.
   `http://192.168.1.23:8000`, and make sure the device is on the same Wi-Fi.

## Verifying it actually works

**Caching:**
1. Run the app once with the dev server up and Wi-Fi/data on — filter Logcat
   for tag `ChatWebView`, you should see six `NETWORK <-` lines (index.html,
   styles.css, data.js, chat-list.js, chat-detail.js, chat-new.js).
2. Force-stop and relaunch the app (still online) — same six lines should now
   read `CACHE <-` instead.
3. Turn on airplane mode, force-stop, relaunch — the app should still load
   fully from cache.

**Back button:** open a chat, press hardware back — should return to the
list, not exit the app. Open the `+` add-contact form, press back — same
thing. Press back once more from the list — now it should exit. Open the
drawer, press back — should close the drawer, not exit or navigate.

**Nav drawer:** on the list screen, tap the hamburger icon (top-left) —
drawer opens, "Messages" shown highlighted. Tap "Messages" — drawer closes,
still on the list. Open a chat thread or the add-contact form — the hamburger
icon should be gone entirely, and swiping in from the left edge shouldn't
open anything.

**Notifications:** from the list screen, open the drawer and tap "Simulate
notification" — a notification should appear; tapping it should open (or
resume) the app with that thread showing. See "Known limitations" below for
why the suppression behavior can't currently be exercised through the UI at
all.


