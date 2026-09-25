/**
 * WizzoPush - Cordova Plugin for Push Notifications
 * Drop-in replacement for FirebaseX (cordova-plugin-firebasex)
 * 
 * API is 100% compatible with FirebasePlugin for easy migration
 */

var exec = require('cordova/exec');

// Optional POOSH backend integration. Dormant unless configurePoosh({enabled:true,...})
// is called; when disabled, every wrapper below falls through to the native behavior.
// Resolve robustly: prefer the cordova module, fall back to the global clobber, and
// finally a no-op stub so WizzoPush never breaks if the POOSH module is absent.
var poosh = (function() {
    try { return require('./WizzoPushPoosh'); } catch (e) {}
    try { return require('cordova-plugin-wizzopush.WizzoPushPoosh'); } catch (e) {}
    if (typeof window !== 'undefined' && window.WizzoPushPoosh) return window.WizzoPushPoosh;
    return {
        isEnabled: function() { return false; },
        configure: function() { return { enabled: false }; },
        getConfig: function() { return { enabled: false }; },
        getTokenRef: function() { return null; },
        registerToken: function() { return Promise.resolve(null); },
        unregisterToken: function() { return Promise.resolve(null); },
        updateTopics: function() { return Promise.resolve(null); },
        subscribeTopic: function() { return Promise.resolve(false); },
        unsubscribeTopic: function() { return Promise.resolve(false); },
        reportClick: function() { return Promise.resolve(null); },
        onTokenRef: function() { return function() {}; },
        offTokenRef: function() {}
    };
})();

var ensureBooleanFn = function(callback) {
    return function(result) {
        callback(ensureBoolean(result));
    }
};

var ensureBoolean = function(value) {
    if (value === "true") {
        value = true;
    } else if (value === "false") {
        value = false;
    }
    return !!value;
};

// Detect whether an incoming notification represents a user tap (vs a foreground
// data message). Mirrors the cross-version FirebaseX convention used by BHOL.
var wasTapped = function(notification) {
    if (!notification) return false;
    return (
        notification.tap === true ||
        notification.tap === 'background' ||
        notification.tap === 'foreground' ||
        notification.wasTapped === true
    );
};

// Extract a router-navigable path from a push payload.
// Checks original_url first (the real URL before POOSH tracking replacement),
// then link (relative path), then url as last resort.
var extractLink = function(payload) {
    if (!payload) return null;
    var rawUrl = payload.original_url || payload.link || payload.url;
    if (!rawUrl || rawUrl === '') return null;
    if (rawUrl.charAt(0) === '/') return rawUrl;
    try {
        var u = new URL(rawUrl);
        return u.pathname + u.search + u.hash;
    } catch (_) {
        return rawUrl.replace(/^https?:\/\/[^/]+/, '') || null;
    }
};

// Navigation callback set via configureNavigation(). When set, tapped
// notifications auto-navigate without the host app needing any click handler.
var _navigateTo = null;

// Local mirror of the token's topic subscriptions, so POOSH topic targeting can be
// kept in sync. FCM topic broadcasts are independent and keep working regardless.
var subscribedTopics = {};

/**
 * WizzoPush API
 * Compatible with FirebasePlugin for drop-in replacement
 */
var WizzoPush = {
    
    // ==================== Token Management ====================
    
    /**
     * Get the FCM registration token
     * @param {Function} success - Called with token string
     * @param {Function} error - Called with error message
     */
    getToken: function(success, error) {
        exec(function(token) {
            // When POOSH is enabled, transparently report the token before
            // handing it to the app. POOSH failures never affect the app callback.
            if (token && poosh.isEnabled()) {
                poosh.registerToken(token);
            }
            if (success) success(token);
        }, error, "WizzoPush", "getToken", []);
    },

    /**
     * Register callback for token refresh events
     * @param {Function} success - Called each time token is refreshed
     * @param {Function} error - Called on error
     */
    onTokenRefresh: function(success, error) {
        exec(function(token) {
            // A refreshed token replaces the old one in POOSH (force re-register).
            if (token && poosh.isEnabled()) {
                poosh.registerToken(token, true);
            }
            if (success) success(token);
        }, error, "WizzoPush", "onTokenRefresh", []);
    },
    
    /**
     * Get APNS token (iOS only)
     * @param {Function} success - Called with APNS token string
     * @param {Function} error - Called with error message
     */
    getAPNSToken: function(success, error) {
        exec(success, error, "WizzoPush", "getAPNSToken", []);
    },
    
    // ==================== Permissions ====================
    
    /**
     * Check if push notification permission is granted
     * @param {Function} success - Called with boolean
     * @param {Function} error - Called with error message
     */
    hasPermission: function(success, error) {
        exec(ensureBooleanFn(success), error, "WizzoPush", "hasPermission", []);
    },
    
    /**
     * Request push notification permission
     * @param {Function} success - Called with boolean (granted or not)
     * @param {Function} error - Called with error message
     */
    grantPermission: function(success, error) {
        exec(ensureBooleanFn(success), error, "WizzoPush", "grantPermission", []);
    },
    
    // ==================== Message Handling ====================
    
    /**
     * Register callback for incoming push messages
     * Called for both foreground messages and notification taps
     * @param {Function} success - Called with message payload
     * @param {Function} error - Called on error
     */
    onMessageReceived: function(success, error) {
        exec(function(message) {
            // When POOSH is enabled and this is a notification tap, report the
            // click to POOSH (POST <trackingUrl>/click). Always forward to the app.
            if (poosh.isEnabled() && wasTapped(message)) {
                poosh.reportClick(message);
            }
            // Auto-navigate on tap if configureNavigation was called
            if (_navigateTo && wasTapped(message)) {
                var link = extractLink(message);
                if (link && link !== '/') {
                    setTimeout(function() { _navigateTo(link); }, 300);
                }
            }
            if (success) success(message);
        }, error, "WizzoPush", "onMessageReceived", []);
    },
    
    /**
     * Get the push payload that launched the app (cold start)
     * @param {Function} success - Called with payload or null
     * @param {Function} error - Called on error
     */
    getInitialPushPayload: function(success, error) {
        exec(function(payload) {
            // Cold-start tap: the launching payload is also a click. Report it to
            // POOSH so opens that bypass onMessageReceived are still counted.
            if (payload && poosh.isEnabled()) {
                poosh.reportClick(payload);
            }
            // Auto-navigate on cold start if configureNavigation was called
            if (payload && _navigateTo) {
                var link = extractLink(payload);
                if (link && link !== '/') {
                    setTimeout(function() { _navigateTo(link); }, 1500);
                }
            }
            if (success) success(payload);
        }, error, "WizzoPush", "getInitialPushPayload", []);
    },
    
    // ==================== Notification Management ====================
    
    /**
     * Clear all notifications from the notification tray
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    clearAllNotifications: function(success, error) {
        exec(success, error, "WizzoPush", "clearAllNotifications", []);
    },

    /**
     * Android: dismiss the conversation card of a data-only push (the one drawn by the
     * plugin for a `conversation_id`), for example when the user opened that conversation
     * inside the app. No-op on iOS.
     */
    clearConversation: function(conversationId, success, error) {
        exec(success, error, "WizzoPush", "clearConversation", [String(conversationId || "")]);
    },
    
    // Alias for compatibility
    clearNotifications: function(success, error) {
        this.clearAllNotifications(success, error);
    },
    
    /**
     * Set the app badge number (iOS only)
     * @param {Number} number - Badge count
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setBadgeNumber: function(number, success, error) {
        exec(success, error, "WizzoPush", "setBadgeNumber", [number]);
    },
    
    /**
     * Get the current badge number (iOS only)
     * @param {Function} success - Called with badge number
     * @param {Function} error - Called on error
     */
    getBadgeNumber: function(success, error) {
        exec(success, error, "WizzoPush", "getBadgeNumber", []);
    },
    
    // ==================== Topics ====================
    
    /**
     * Subscribe to a topic
     * @param {String} topic - Topic name
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    subscribe: function(topic, success, error) {
        exec(function() {
            // Keep POOSH's topic targeting in sync with FCM topic subscriptions.
            // ADDITIVE: add just this topic on POOSH (POST /push/subscribe), so a
            // reserved audience topic (e.g. "general") and other follows survive.
            // (updateTopics is replace-all and would clobber them from the empty mirror.)
            if (topic && poosh.isEnabled()) {
                subscribedTopics[topic] = true;
                // POOSH /push/subscribe 404s if the token isn't registered yet
                // (e.g. user follows a channel on a fresh device before any
                // explicit getToken). Guarantee a registered token first, then
                // subscribe additively — so channel-follow "just works".
                WizzoPush._ensurePooshToken(function() {
                    poosh.subscribeTopic(topic);
                });
            }
            if (success) success();
        }, error, "WizzoPush", "subscribe", [topic]);
    },

    /**
     * Unsubscribe from a topic
     * @param {String} topic - Topic name
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    unsubscribe: function(topic, success, error) {
        exec(function() {
            // ADDITIVE: remove just this topic on POOSH (POST /push/unsubscribe),
            // leaving the rest of the token's topic list intact.
            if (topic && poosh.isEnabled()) {
                delete subscribedTopics[topic];
                WizzoPush._ensurePooshToken(function() {
                    poosh.unsubscribeTopic(topic);
                });
            }
            if (success) success();
        }, error, "WizzoPush", "unsubscribe", [topic]);
    },
    
    // ==================== Token Deletion ====================
    
    /**
     * Unregister from FCM (deletes the token)
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    unregister: function(success, error) {
        var doNativeUnregister = function() {
            exec(success, error, "WizzoPush", "unregister", []);
        };
        if (poosh.isEnabled()) {
            // Deactivate the token in POOSH before Firebase deletes it locally.
            // Read the current token first; proceed regardless of POOSH outcome.
            exec(function(token) {
                poosh.unregisterToken(token).then(doNativeUnregister, doNativeUnregister);
            }, function() {
                // Couldn't read token (rare) — still deactivate the cached one, then delete.
                poosh.unregisterToken().then(doNativeUnregister, doNativeUnregister);
            }, "WizzoPush", "getToken", []);
        } else {
            doNativeUnregister();
        }
    },
    
    // Alias for compatibility with some FirebaseX versions
    deleteInstanceId: function(success, error) {
        this.unregister(success, error);
    },
    
    // ==================== Channels (Android only) ====================
    
    /**
     * Create a notification channel (Android 8+)
     * @param {Object} options - Channel options
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    createChannel: function(options, success, error) {
        exec(success, error, "WizzoPush", "createChannel", [options]);
    },
    
    /**
     * Delete a notification channel (Android 8+)
     * @param {String} channelId - Channel ID to delete
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    deleteChannel: function(channelId, success, error) {
        exec(success, error, "WizzoPush", "deleteChannel", [channelId]);
    },
    
    /**
     * Set the default notification channel (Android 8+)
     * @param {Object} options - Channel options
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setDefaultChannel: function(options, success, error) {
        exec(success, error, "WizzoPush", "setDefaultChannel", [options]);
    },
    
    /**
     * List all notification channels (Android 8+)
     * @param {Function} success - Called with array of channels
     * @param {Function} error - Called on error
     */
    listChannels: function(success, error) {
        exec(success, error, "WizzoPush", "listChannels", []);
    },
    
    // ==================== Auto-init ====================
    
    /**
     * Check if FCM auto-init is enabled
     * @param {Function} success - Called with boolean
     * @param {Function} error - Called on error
     */
    isAutoInitEnabled: function(success, error) {
        exec(success, error, "WizzoPush", "isAutoInitEnabled", []);
    },
    
    /**
     * Enable or disable FCM auto-init
     * @param {Boolean} enabled - Whether to enable
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setAutoInitEnabled: function(enabled, success, error) {
        exec(success, error, "WizzoPush", "setAutoInitEnabled", [!!enabled]);
    },
    
    // ==================== WizzoPush Extensions ====================
    
    /**
     * Get diagnostic information (WizzoPush specific)
     * @param {Function} success - Called with diagnostics object
     * @param {Function} error - Called on error
     */
    getDiagnostics: function(success, error) {
        exec(success, error, "WizzoPush", "getDiagnostics", []);
    },
    
    /**
     * Clear diagnostic logs (WizzoPush specific)
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    clearDiagnostics: function(success, error) {
        exec(success, error, "WizzoPush", "clearDiagnostics", []);
    },
    
    // ==================== Firebase Analytics ====================
    
    /**
     * Log an analytics event
     * @param {String} eventName - Event name
     * @param {Object} params - Event parameters (optional)
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    logEvent: function(eventName, params, success, error) {
        if (typeof params === 'function') {
            error = success;
            success = params;
            params = {};
        }
        exec(success, error, "WizzoPush", "logEvent", [eventName, params || {}]);
    },
    
    /**
     * Enable or disable analytics collection
     * @param {Boolean} enabled - Whether to enable collection
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setAnalyticsCollectionEnabled: function(enabled, success, error) {
        exec(success, error, "WizzoPush", "setAnalyticsCollectionEnabled", [!!enabled]);
    },
    
    /**
     * Set the user ID for analytics
     * @param {String} userId - User ID (null to clear)
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setUserId: function(userId, success, error) {
        exec(success, error, "WizzoPush", "setUserId", [userId]);
    },
    
    /**
     * Set a user property for analytics
     * @param {String} name - Property name
     * @param {String} value - Property value (null to clear)
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setUserProperty: function(name, value, success, error) {
        exec(success, error, "WizzoPush", "setUserProperty", [name, value]);
    },
    
    /**
     * Set the current screen name for analytics
     * @param {String} screenName - Screen name
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    setScreenName: function(screenName, success, error) {
        exec(success, error, "WizzoPush", "setScreenName", [screenName]);
    },
    
    // ==================== Google Sign-In ====================
    
    /**
     * Authenticate user with Google Sign-In
     * @param {String} webClientId - Google OAuth web client ID
     * @param {Function} success - Called with credential object {idToken, email, displayName, photoUrl, id}
     * @param {Function} error - Called on error or cancellation
     */
    authenticateUserWithGoogle: function(webClientId, success, error) {
        exec(success, error, "WizzoPush", "authenticateUserWithGoogle", [webClientId]);
    },
    
    /**
     * Sign out from Google
     * @param {Function} success - Called on success
     * @param {Function} error - Called on error
     */
    signOutGoogle: function(success, error) {
        exec(success, error, "WizzoPush", "signOutGoogle", []);
    },

    // ==================== Microsoft Sign-In ====================

    /**
     * Authenticate user with Microsoft Sign-In via MSAL
     * @param {Function} success - Called with credential object {accessToken, idToken, email, displayName, uid, authMethod}
     * @param {Function} error - Called on error or cancellation
     */
    authenticateUserWithMicrosoft: function(success, error) {
        exec(success, error, "WizzoPush", "authenticateUserWithMicrosoft", []);
    },

    // ==================== Navigation ====================

    /**
     * Configure automatic deep-link navigation on notification tap.
     * Once configured, the SDK automatically extracts the link from tapped
     * notifications (from `link` or `url` fields) and calls navigateTo(path).
     * The host app only needs to pass its router navigation function.
     *
     * @param {Object} options - { navigateTo: Function }
     *   navigateTo receives a relative path (e.g. "/story/123") and should
     *   navigate the app to that route.
     */
    configureNavigation: function(options) {
        if (options && typeof options.navigateTo === 'function') {
            _navigateTo = options.navigateTo;
        }
    },

    // ==================== POOSH Backend Integration ====================

    /**
     * Configure (and enable/disable) the optional POOSH backend integration.
     *
     * When enabled, the token lifecycle (register / refresh / delete), topic
     * subscriptions and notification clicks are reported DIRECTLY to a POOSH
     * tenant — no app backend required. The FCM token still comes from the app's
     * own Firebase config; POOSH must use the SAME Firebase project to deliver to
     * these tokens. This is fully reversible: call with { enabled: false } to revert
     * to local-only (FirebaseX-compatible) behavior.
     *
     * @param {Object} options - { enabled: Boolean, apiKey: String, baseUrl?: String }
     * @param {Function} [success] - Called with the resulting config snapshot
     * @param {Function} [error] - Called on error
     */
    configurePoosh: function(options, success, error) {
        try {
            var config = poosh.configure(options || {});
            // If enabled with autoRegister (default true), fetch + report the token now
            // so the switch is transparent without the app calling getToken explicitly.
            if (poosh.isEnabled() && (!options || options.autoRegister !== false)) {
                this._pooshAutoRegister();
            }
            if (success) success(config);
        } catch (e) {
            if (error) error('' + e);
        }
    },

    /**
     * Whether the POOSH integration is currently enabled (and has an API key).
     * @param {Function} success - Called with boolean
     */
    isPooshEnabled: function(success, error) {
        if (success) success(poosh.isEnabled());
    },

    /**
     * Get the POOSH token reference (tk_...) for the current device, if registered.
     * @param {Function} success - Called with the token_ref string (or null)
     */
    getPooshTokenRef: function(success, error) {
        if (success) success(poosh.getTokenRef());
    },

    /**
     * Subscribe to POOSH token_ref events. The callback fires with
     * { tokenRef, token, platform } each time the device is (re-)registered with
     * POOSH and a token_ref is confirmed. If a token_ref is already known, the
     * callback is invoked immediately, so subscribing late (e.g. after the user
     * logs in) never misses the ref.
     *
     * This is the hook the host app uses to link the device's POOSH token_ref to
     * the logged-in user — entirely from the device, with no server-side POOSH
     * registration required.
     *
     *   WizzoPush.onPooshTokenRef(function(info) {
     *     // info.tokenRef is the tk_... reference; save it onto the user.
     *   });
     *
     * @param {Function} callback - Called with { tokenRef, token, platform }
     * @returns {Function} an unsubscribe function
     */
    onPooshTokenRef: function(callback) {
        return poosh.onTokenRef(callback);
    },

    /**
     * Remove a previously-registered POOSH token_ref listener.
     * @param {Function} callback
     */
    offPooshTokenRef: function(callback) {
        poosh.offTokenRef(callback);
    },

    /**
     * Internal: ensure this device has a POOSH-registered token, then run cb.
     * POOSH's additive /push/subscribe needs the token to already exist server-side
     * (it 404s otherwise). If a token_ref is already cached, cb runs immediately.
     * Otherwise we read the live FCM token via native getToken and register it with
     * POOSH first (registerToken sets state.lastToken), then run cb. cb always runs
     * — even if registration fails — so the caller's success path is never blocked;
     * the subscribe call will simply no-op if no token could be obtained.
     * @private
     */
    _ensurePooshToken: function(cb) {
        if (!poosh.isEnabled()) { if (cb) cb(); return; }
        if (poosh.getTokenRef()) { if (cb) cb(); return; }
        exec(function(token) {
            if (token) {
                poosh.registerToken(token).then(function() { if (cb) cb(); }, function() { if (cb) cb(); });
            } else {
                if (cb) cb();
            }
        }, function() { if (cb) cb(); }, "WizzoPush", "getToken", []);
    },

    /**
     * Internal: fetch the FCM token (with iOS retry, since APNS registration is
     * async) and report it to POOSH. Used on enable and on deviceready auto-init.
     * @private
     */
    _pooshAutoRegister: function(retriesLeft, delay) {
        if (typeof retriesLeft === 'undefined') retriesLeft = 3;
        if (typeof delay === 'undefined') delay = 3000;
        exec(function(token) {
            if (token) {
                poosh.registerToken(token);
            } else if (retriesLeft > 0) {
                setTimeout(function() {
                    WizzoPush._pooshAutoRegister(retriesLeft - 1, delay + 2000);
                }, delay);
            }
        }, function() { /* ignore — getToken may fail before permission granted */ },
        "WizzoPush", "getToken", []);
    }
};

// FirebaseX compatibility alias - allows apps using window.FirebasePlugin to work without code changes
if (typeof window !== 'undefined') {
    window.FirebasePlugin = WizzoPush;

    // If POOSH was left enabled from a previous session, re-report the token on
    // startup so registrations stay fresh without the app doing anything.
    document.addEventListener('deviceready', function() {
        if (poosh.isEnabled()) {
            WizzoPush._pooshAutoRegister();
        }
    }, false);
}

module.exports = WizzoPush;
