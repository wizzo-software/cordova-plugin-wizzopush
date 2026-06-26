/**
 * WizzoPushPoosh - Optional POOSH backend integration for WizzoPush.
 *
 * When enabled, this layer reports the device's FCM token, token refreshes,
 * unregisters, topic changes and notification clicks DIRECTLY to a POOSH tenant
 * (https://api.poosh.work) using the tenant's API key. It does NOT touch
 * Firebase: the FCM token still comes from the app's own Firebase config; we only
 * forward it to POOSH.
 *
 * This is an OPTIONAL, instantly-reversible add-on. When disabled (the default)
 * WizzoPush behaves exactly as before — no network calls are made here.
 *
 * Every network call degrades gracefully: a POOSH failure NEVER breaks the host
 * app (errors are swallowed and logged), since push delivery itself goes through
 * FCM regardless of whether POOSH reporting succeeds.
 *
 * Wired into the public API by www/WizzoPush.js. Apps normally interact with it
 * via WizzoPush.configurePoosh({ enabled, apiKey, baseUrl }).
 */

var STORAGE_KEY = 'wizzopush_poosh';
var DEFAULT_BASE_URL = 'https://api.poosh.work';
// Skip re-registering the same token within this window (matches the web SDK guard).
var REGISTER_DEDUP_MS = 24 * 60 * 60 * 1000;

// Subscribers notified whenever a token_ref is obtained from POOSH. The host app
// (e.g. main.js) registers here to link the token_ref to the logged-in user.
var tokenRefListeners = [];

/**
 * Persisted config + state.
 * { enabled, apiKey, baseUrl, lastToken, lastTokenRef, lastRegisterTs }
 */
var state = {
    enabled: false,
    apiKey: '',
    baseUrl: DEFAULT_BASE_URL,
    lastToken: null,
    lastTokenRef: null,
    lastRegisterTs: 0
};

// ==================== Persistence ====================

function loadState() {
    try {
        var raw = (typeof localStorage !== 'undefined') ? localStorage.getItem(STORAGE_KEY) : null;
        if (raw) {
            var parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object') {
                for (var k in parsed) {
                    if (Object.prototype.hasOwnProperty.call(parsed, k)) state[k] = parsed[k];
                }
            }
        }
    } catch (e) {
        console.warn('[WizzoPush/Poosh] loadState failed:', e);
    }
    if (!state.baseUrl) state.baseUrl = DEFAULT_BASE_URL;
}

function saveState() {
    try {
        if (typeof localStorage !== 'undefined') {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
        }
    } catch (e) {
        console.warn('[WizzoPush/Poosh] saveState failed:', e);
    }
}

// ==================== Helpers ====================

function getPlatform() {
    try {
        if (typeof cordova !== 'undefined' && cordova.platformId) {
            return cordova.platformId === 'ios' ? 'ios' : 'android';
        }
    } catch (e) { /* ignore */ }
    var ua = (typeof navigator !== 'undefined' && navigator.userAgent) ? navigator.userAgent : '';
    return /iPad|iPhone|iPod/.test(ua) ? 'ios' : 'android';
}

function normalizeBaseUrl(url) {
    if (!url) return DEFAULT_BASE_URL;
    return String(url).replace(/\/+$/, '');
}

/**
 * Thin POST/PUT helper. Returns the parsed JSON body, or null on any failure.
 * Never throws — POOSH connectivity must not break the app.
 */
function request(method, path, body) {
    if (typeof fetch === 'undefined') {
        console.warn('[WizzoPush/Poosh] fetch unavailable; skipping ' + method + ' ' + path);
        return Promise.resolve(null);
    }
    var url = normalizeBaseUrl(state.baseUrl) + path;
    var headers = { 'Content-Type': 'application/json' };
    if (state.apiKey) headers['x-api-key'] = state.apiKey;
    return fetch(url, {
        method: method,
        headers: headers,
        body: body ? JSON.stringify(body) : undefined
    }).then(function (res) {
        if (!res.ok) {
            console.warn('[WizzoPush/Poosh] ' + method + ' ' + path + ' -> HTTP ' + res.status);
            return null;
        }
        return res.json().catch(function () { return null; });
    }).catch(function (e) {
        console.warn('[WizzoPush/Poosh] ' + method + ' ' + path + ' failed:', e);
        return null;
    });
}

/**
 * Notify all token_ref subscribers (and fire a DOM event) that a token_ref is
 * available. Called whenever POOSH returns/confirms a token_ref. Each callback
 * is isolated so one bad listener can't break the others or the SDK.
 *
 * Listeners receive an object: { tokenRef, token, platform }.
 * A 'wizzopush.tokenref' document event is also dispatched with the same detail,
 * so the app can subscribe either way.
 */
function emitTokenRef(tokenRef, token) {
    if (!tokenRef) return;
    var detail = { tokenRef: tokenRef, token: token || null, platform: getPlatform() };
    for (var i = 0; i < tokenRefListeners.length; i++) {
        try {
            tokenRefListeners[i](detail);
        } catch (e) {
            console.warn('[WizzoPush/Poosh] tokenRef listener error:', e);
        }
    }
    try {
        if (typeof document !== 'undefined' && typeof document.dispatchEvent === 'function') {
            var ev;
            if (typeof CustomEvent === 'function') {
                ev = new CustomEvent('wizzopush.tokenref', { detail: detail });
            } else if (typeof document.createEvent === 'function') {
                ev = document.createEvent('CustomEvent');
                ev.initCustomEvent('wizzopush.tokenref', false, false, detail);
            }
            if (ev) document.dispatchEvent(ev);
        }
    } catch (e) {
        console.warn('[WizzoPush/Poosh] tokenRef dispatch error:', e);
    }
}

/**
 * Extract the full POOSH tracking URL from an incoming notification payload.
 * Mirrors the BHOL convention: clicks are reported by POSTing to
 * `<trackingUrl>/click` where the tracking URL contains the "/t/" path.
 */
function extractTrackingUrl(payload) {
    if (!payload || typeof payload !== 'object') return null;
    var url = payload.url || (payload.data && payload.data.url) || '';
    if (url && String(url).indexOf('/t/') !== -1) return String(url);
    return null;
}

// ==================== Public API ====================

var WizzoPushPoosh = {

    /**
     * Apply / replace POOSH configuration.
     * @param {Object} options { enabled, apiKey, baseUrl }
     * @returns {Object} a snapshot of the resulting config
     */
    configure: function (options) {
        options = options || {};

        var wasEnabled = this.isEnabled();
        var willDisable = (typeof options.enabled !== 'undefined') && !options.enabled;

        // Disabling (opt-out) → deactivate this device's token in POOSH first,
        // while we still hold the token + api key, so the user actually stops
        // receiving push. unregisterToken() clears the cached token/ref on success.
        if (wasEnabled && willDisable && state.lastToken) {
            this.unregisterToken(state.lastToken);
        }

        if (typeof options.enabled !== 'undefined') state.enabled = !!options.enabled;
        if (typeof options.apiKey !== 'undefined') state.apiKey = options.apiKey || '';
        if (typeof options.baseUrl !== 'undefined') state.baseUrl = normalizeBaseUrl(options.baseUrl);
        saveState();

        // Turning on with a token already in hand → register immediately so the
        // switch is "transparent" without waiting for the next getToken.
        if (state.enabled && state.apiKey && state.lastToken) {
            this.registerToken(state.lastToken, true);
        }
        return this.getConfig();
    },

    getConfig: function () {
        return {
            enabled: state.enabled,
            apiKey: state.apiKey,
            baseUrl: normalizeBaseUrl(state.baseUrl),
            tokenRef: state.lastTokenRef
        };
    },

    isEnabled: function () {
        return !!(state.enabled && state.apiKey);
    },

    getTokenRef: function () {
        return state.lastTokenRef;
    },

    /**
     * Report the FCM token to POOSH (POST /push/register-token).
     * Deduped: skips if the same token was registered within REGISTER_DEDUP_MS,
     * unless `force` is true.
     * @returns {Promise<string|null>} the token_ref, or null on failure/skip
     */
    registerToken: function (token, force) {
        if (!this.isEnabled() || !token) return Promise.resolve(null);

        var sameToken = (state.lastToken === token);
        var fresh = sameToken && state.lastRegisterTs &&
            (Date.now() - state.lastRegisterTs < REGISTER_DEDUP_MS);
        if (fresh && !force) {
            return Promise.resolve(state.lastTokenRef);
        }

        return request('POST', '/push/register-token', {
            token: token,
            platform: getPlatform()
        }).then(function (data) {
            state.lastToken = token;
            state.lastRegisterTs = Date.now();
            if (data && data.token_ref) state.lastTokenRef = data.token_ref;
            saveState();
            console.log('[WizzoPush/Poosh] token registered, ref=' + state.lastTokenRef);
            // Notify the app (e.g. main.js) so it can link this token_ref to the
            // logged-in user. Fires on every confirmed registration, so a listener
            // that subscribes late still gets the ref via getTokenRef().
            if (state.lastTokenRef) emitTokenRef(state.lastTokenRef, token);
            return state.lastTokenRef;
        });
    },

    /**
     * Subscribe to token_ref events. The callback fires with { tokenRef, token,
     * platform } each time POOSH confirms a registration. If a token_ref is
     * already known, the callback is invoked immediately (so a late subscriber
     * — e.g. after deviceready — never misses the ref).
     * @param {Function} callback
     * @returns {Function} an unsubscribe function
     */
    onTokenRef: function (callback) {
        if (typeof callback !== 'function') return function () {};
        tokenRefListeners.push(callback);
        if (state.lastTokenRef) {
            try {
                callback({ tokenRef: state.lastTokenRef, token: state.lastToken, platform: getPlatform() });
            } catch (e) {
                console.warn('[WizzoPush/Poosh] onTokenRef immediate callback error:', e);
            }
        }
        return function unsubscribe() {
            var idx = tokenRefListeners.indexOf(callback);
            if (idx !== -1) tokenRefListeners.splice(idx, 1);
        };
    },

    /**
     * Remove a previously-registered token_ref listener.
     * @param {Function} callback
     */
    offTokenRef: function (callback) {
        var idx = tokenRefListeners.indexOf(callback);
        if (idx !== -1) tokenRefListeners.splice(idx, 1);
    },

    /**
     * Unregister the token from POOSH (POST /push/unregister-token).
     */
    unregisterToken: function (token) {
        if (!this.isEnabled()) return Promise.resolve(null);
        token = token || state.lastToken;
        if (!token) return Promise.resolve(null);
        return request('POST', '/push/unregister-token', { token: token }).then(function (data) {
            // Clear local registration cache so a future getToken re-registers.
            state.lastToken = null;
            state.lastTokenRef = null;
            state.lastRegisterTs = 0;
            saveState();
            console.log('[WizzoPush/Poosh] token unregistered');
            return data;
        });
    },

    /**
     * Update the token's topic subscriptions on POOSH (PUT /push/update-topics).
     * REPLACE-ALL: overwrites the token's entire topic list. Prefer the additive
     * subscribeTopic/unsubscribeTopic below for per-channel opt-in, so a reserved
     * audience topic (e.g. "general") and other follows are not clobbered.
     * @param {string[]} topics the full topic list for this token
     */
    updateTopics: function (topics, token) {
        if (!this.isEnabled()) return Promise.resolve(null);
        token = token || state.lastToken;
        if (!token || !topics) return Promise.resolve(null);
        return request('PUT', '/push/update-topics', { token: token, topics: topics });
    },

    /**
     * Additively subscribe this device's token to ONE topic on POOSH
     * (POST /push/subscribe). The server appends the topic if absent and leaves
     * every other topic on the token intact (atomic JSON_ARRAY_APPEND) — so a
     * reserved topic like "general" and any existing follows survive. This is the
     * correct primitive for free-form per-channel opt-in ("writer-5", "category-2").
     * @param {string} topic
     * @param {string} [token] defaults to the last registered token
     * @returns {Promise<boolean>} true on success, false on failure/skip
     */
    subscribeTopic: function (topic, token) {
        if (!this.isEnabled() || !topic) return Promise.resolve(false);
        token = token || state.lastToken;
        if (!token) return Promise.resolve(false);
        return request('POST', '/push/subscribe', { token: token, topic: topic })
            .then(function (data) { return !!(data && data.success); });
    },

    /**
     * Additively unsubscribe this device's token from ONE topic on POOSH
     * (POST /push/unsubscribe). Removes just that topic, preserving the rest of
     * the token's topic list (atomic JSON_REMOVE). The mirror of subscribeTopic.
     * @param {string} topic
     * @param {string} [token] defaults to the last registered token
     * @returns {Promise<boolean>} true on success, false on failure/skip
     */
    unsubscribeTopic: function (topic, token) {
        if (!this.isEnabled() || !topic) return Promise.resolve(false);
        token = token || state.lastToken;
        if (!token) return Promise.resolve(false);
        return request('POST', '/push/unsubscribe', { token: token, topic: topic })
            .then(function (data) { return !!(data && data.success); });
    },

    /**
     * Report a notification click to POOSH.
     * Uses the proven BHOL pattern: POST `<trackingUrl>/click` for any payload
     * whose url contains "/t/". No-op otherwise. This endpoint is public (no auth).
     */
    reportClick: function (payload) {
        if (!this.isEnabled()) return Promise.resolve(null);
        var trackingUrl = extractTrackingUrl(payload);
        if (!trackingUrl) return Promise.resolve(null);
        if (typeof fetch === 'undefined') return Promise.resolve(null);
        return fetch(trackingUrl + '/click', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
        }).then(function () {
            console.log('[WizzoPush/Poosh] click reported');
            return true;
        }).catch(function (e) {
            console.warn('[WizzoPush/Poosh] reportClick failed:', e);
            return null;
        });
    }
};

loadState();

if (typeof window !== 'undefined') {
    window.WizzoPushPoosh = WizzoPushPoosh;
}

module.exports = WizzoPushPoosh;
