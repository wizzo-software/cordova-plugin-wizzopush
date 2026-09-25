package coffee.sunday.wizzopush;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.OAuthProvider;


import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * WizzoPush Cordova Plugin
 * Drop-in replacement for FirebaseX push notification functionality
 */
public class WizzoPushPlugin extends CordovaPlugin {
    
    private static final String TAG = "WizzoPush";
    private static final String ANALYTICS_ABSENT =
        "Firebase Analytics is not part of this build";
    private static final String MSAL_ABSENT =
        "Microsoft Sign-In is not part of this build";
    private static final String PREFS_NAME = "WizzoPushPrefs";
    private static final String KEY_INITIAL_PAYLOAD = "initialPushPayload";
    private static final int PERMISSION_REQUEST_CODE = 12345;
    private static final int GOOGLE_SIGN_IN_REQUEST_CODE = 12346;
    
    private static WizzoPushPlugin instance;
    private CallbackContext messageReceivedCallback;
    private CallbackContext tokenRefreshCallback;
    private CallbackContext permissionCallback;
    private CallbackContext googleSignInCallback;
    private WizzoPushAnalytics analytics;
    private GoogleSignInClient googleSignInClient;
    private boolean initialized = false;
    
    public static WizzoPushPlugin getInstance() {
        return instance;
    }
    
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        instance = this;
        Log.d(TAG, "WizzoPush plugin initialized");
        
        // Create default notification channel for Android 8+
        createDefaultChannel();
        
        // Firebase Analytics, when this build has it. An app that excluded the artifact
        // (to keep the advertising-id permissions out of its manifest) gets null here and
        // everything else in the plugin carries on unchanged. The try is belt and braces:
        // createOrNull already catches, but the class itself may fail to load at this call.
        try {
            analytics = WizzoPushAnalytics.createOrNull(cordova.getActivity());
        } catch (Throwable t) {
            analytics = null;
        }
        if (analytics == null) {
            Log.d(TAG, "Firebase Analytics is not part of this build - analytics calls are no-ops");
        }
        
        initialized = true;
        WizzoPushNotifier.setForeground(true);
    }
    
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        WizzoPushNotifier.setForeground(true);
    }
    
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        WizzoPushNotifier.setForeground(false);
    }
    
    @Override
    public void onDestroy() {
        WizzoPushNotifier.setForeground(false);
        super.onDestroy();
    }
    
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent called");
        
        // Check if this intent came from a notification tap
        if (intent != null && intent.getExtras() != null) {
            Bundle extras = intent.getExtras();
            JSONObject payload = bundleToJson(extras);
            
            // Only process if there's meaningful data (not just system keys)
            if (payload.length() > 0) {
                Log.d(TAG, "Notification tap detected with payload: " + payload.toString());
                onNotificationTapped(payload);
            }
        }
    }
    
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute: " + action);
        
        switch (action) {
            case "getToken":
                getToken(callbackContext);
                return true;
                
            case "onTokenRefresh":
                registerTokenRefreshCallback(callbackContext);
                return true;
                
            case "hasPermission":
                hasPermission(callbackContext);
                return true;
                
            case "grantPermission":
                grantPermission(callbackContext);
                return true;
                
            case "onMessageReceived":
                registerMessageReceivedCallback(callbackContext);
                return true;
                
            case "getInitialPushPayload":
                getInitialPushPayload(callbackContext);
                return true;
                
            case "clearConversation":
                WizzoPushNotifier.cancelConversation(cordova.getActivity(), args.optString(0, null));
                callbackContext.success();
                return true;
                
            case "clearAllNotifications":
                clearAllNotifications(callbackContext);
                return true;
                
            case "subscribe":
                subscribe(args.getString(0), callbackContext);
                return true;
                
            case "unsubscribe":
                unsubscribe(args.getString(0), callbackContext);
                return true;
                
            case "unregister":
                unregister(callbackContext);
                return true;
                
            case "createChannel":
                createChannel(args.getJSONObject(0), callbackContext);
                return true;
                
            case "deleteChannel":
                deleteChannel(args.getString(0), callbackContext);
                return true;
                
            case "setDefaultChannel":
                setDefaultChannel(args.getJSONObject(0), callbackContext);
                return true;
                
            case "listChannels":
                listChannels(callbackContext);
                return true;
                
            case "isAutoInitEnabled":
                isAutoInitEnabled(callbackContext);
                return true;
                
            case "setAutoInitEnabled":
                setAutoInitEnabled(args.getBoolean(0), callbackContext);
                return true;
                
            case "getDiagnostics":
                getDiagnostics(callbackContext);
                return true;
                
            case "clearDiagnostics":
                clearDiagnostics(callbackContext);
                return true;
            
            // ==================== Analytics ====================
            case "logEvent":
                logEvent(args.getString(0), args.optJSONObject(1), callbackContext);
                return true;
                
            case "setAnalyticsCollectionEnabled":
                setAnalyticsCollectionEnabled(args.getBoolean(0), callbackContext);
                return true;
                
            case "setUserId":
                setUserId(args.optString(0, null), callbackContext);
                return true;
                
            case "setUserProperty":
                setUserProperty(args.getString(0), args.optString(1, null), callbackContext);
                return true;
                
            case "setScreenName":
                setScreenName(args.getString(0), callbackContext);
                return true;
            
            // ==================== Google Sign-In ====================
            case "authenticateUserWithGoogle":
                authenticateUserWithGoogle(args.getString(0), callbackContext);
                return true;
                
            case "signOutGoogle":
                signOutGoogle(callbackContext);
                return true;

            // ==================== Microsoft Sign-In ====================
            case "authenticateUserWithMicrosoft":
                authenticateUserWithMicrosoft(callbackContext);
                return true;

            default:
                Log.w(TAG, "Unknown action: " + action);
                return false;
        }
    }
    
    // ==================== Token Management ====================
    
    private void getToken(CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    String token = task.getResult();
                    Log.d(TAG, "FCM Token: " + token);
                    callbackContext.success(token);
                } else {
                    Log.e(TAG, "Failed to get FCM token", task.getException());
                    callbackContext.error("Failed to get token: " + task.getException().getMessage());
                }
            });
    }
    
    private void registerTokenRefreshCallback(CallbackContext callbackContext) {
        tokenRefreshCallback = callbackContext;
        // Keep the callback for persistent use
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }
    
    /**
     * Called by WizzoFirebaseMessagingService when token is refreshed
     */
    public void onNewToken(String token) {
        Log.d(TAG, "Token refreshed: " + token);
        if (tokenRefreshCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, token);
            result.setKeepCallback(true);
            tokenRefreshCallback.sendPluginResult(result);
        }
    }
    
    // ==================== Permissions ====================
    
    private void hasPermission(CallbackContext callbackContext) {
        boolean hasPermission;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires POST_NOTIFICATIONS permission
            hasPermission = ContextCompat.checkSelfPermission(
                cordova.getActivity(), 
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        } else {
            // Older Android versions - check if notifications are enabled
            hasPermission = NotificationManagerCompat.from(cordova.getActivity()).areNotificationsEnabled();
        }
        
        callbackContext.success(hasPermission ? "true" : "false");
    }
    
    private void grantPermission(CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - request POST_NOTIFICATIONS permission
            if (ContextCompat.checkSelfPermission(cordova.getActivity(), 
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                callbackContext.success("true");
            } else {
                permissionCallback = callbackContext;
                // Must go through cordova.requestPermissions, NOT ActivityCompat: Cordova
                // routes a permission result back to a plugin only for request codes IT
                // registered (CordovaInterfaceImpl.permissionResultCallbacks). Asking the
                // Activity directly means onRequestPermissionResult below is never called
                // — the dialog appears, the user taps Allow, and JS waits forever.
                cordova.requestPermissions(
                    this,
                    PERMISSION_REQUEST_CODE,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}
                );
            }
        } else {
            // Older Android - check if notifications are enabled
            boolean enabled = NotificationManagerCompat.from(cordova.getActivity()).areNotificationsEnabled();
            callbackContext.success(enabled ? "true" : "false");
        }
    }
    
    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionCallback != null) {
            // grantResults is EMPTY when the request was cancelled (rotation, tapping
            // outside). That is a "no", not a reason to leave the caller hanging.
            boolean granted = grantResults.length > 0 &&
                             grantResults[0] == PackageManager.PERMISSION_GRANTED;
            permissionCallback.success(granted ? "true" : "false");
            permissionCallback = null;
        }
    }
    
    // ==================== Message Handling ====================
    
    private void registerMessageReceivedCallback(CallbackContext callbackContext) {
        messageReceivedCallback = callbackContext;
        // Keep the callback for persistent use
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }
    
    /**
     * Called by WizzoFirebaseMessagingService when a message is received
     */
    public void onMessageReceived(JSONObject messageData) {
        Log.d(TAG, "Message received: " + messageData.toString());
        
        if (messageReceivedCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, messageData);
            result.setKeepCallback(true);
            messageReceivedCallback.sendPluginResult(result);
        }
    }
    
    /**
     * Called when notification is tapped (from WizzoFirebaseMessagingService or MainActivity)
     */
    public void onNotificationTapped(JSONObject payload) {
        Log.d(TAG, "Notification tapped: " + payload.toString());
        
        // Add tap indicator to payload
        try {
            payload.put("tap", "background");
        } catch (JSONException e) {
            Log.e(TAG, "Error adding tap to payload", e);
        }
        
        if (messageReceivedCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, payload);
            result.setKeepCallback(true);
            messageReceivedCallback.sendPluginResult(result);
        } else {
            // App was killed - save for getInitialPushPayload
            saveInitialPayload(payload);
        }
    }
    
    private void getInitialPushPayload(CallbackContext callbackContext) {
        // First check if app was launched from notification
        Activity activity = cordova.getActivity();
        Intent intent = activity.getIntent();
        
        JSONObject payload = null;
        
        // Check intent extras for notification data
        Bundle extras = intent.getExtras();
        if (extras != null) {
            payload = bundleToJson(extras);
            if (payload.length() > 0) {
                try {
                    payload.put("tap", "background");
                } catch (JSONException e) {
                    Log.e(TAG, "Error adding tap to payload", e);
                }
            } else {
                payload = null;
            }
        }
        
        // If no intent payload, check saved payload
        if (payload == null) {
            payload = getSavedInitialPayload();
        }
        
        if (payload != null) {
            Log.d(TAG, "Initial push payload: " + payload.toString());
            callbackContext.success(payload);
            // Clear saved payload
            clearSavedInitialPayload();
            // Clear the intent extras to prevent duplicate handling
            intent.replaceExtras(new Bundle());
        } else {
            callbackContext.success((String) null);
        }
    }
    
    private void saveInitialPayload(JSONObject payload) {
        SharedPreferences prefs = cordova.getActivity()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_INITIAL_PAYLOAD, payload.toString()).apply();
    }
    
    private JSONObject getSavedInitialPayload() {
        SharedPreferences prefs = cordova.getActivity()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String payloadStr = prefs.getString(KEY_INITIAL_PAYLOAD, null);
        if (payloadStr != null) {
            try {
                return new JSONObject(payloadStr);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing saved payload", e);
            }
        }
        return null;
    }
    
    private void clearSavedInitialPayload() {
        SharedPreferences prefs = cordova.getActivity()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_INITIAL_PAYLOAD).apply();
    }
    
    private JSONObject bundleToJson(Bundle bundle) {
        JSONObject json = new JSONObject();
        if (bundle == null) return json;
        
        for (String key : bundle.keySet()) {
            try {
                Object value = bundle.get(key);
                if (value != null) {
                    // Skip internal Android keys
                    if (key.startsWith("android.") || key.startsWith("google.")) {
                        continue;
                    }
                    json.put(key, value.toString());
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error converting bundle key: " + key, e);
            }
        }
        return json;
    }
    
    // ==================== Notification Management ====================
    
    private void clearAllNotifications(CallbackContext callbackContext) {
        NotificationManager nm = (NotificationManager) cordova.getActivity()
            .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
        callbackContext.success();
    }
    
    // ==================== Topics ====================
    
    private void subscribe(String topic, CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Log.d(TAG, "Subscribed to topic: " + topic);
                    callbackContext.success();
                } else {
                    Log.e(TAG, "Failed to subscribe to topic", task.getException());
                    callbackContext.error("Failed to subscribe: " + task.getException().getMessage());
                }
            });
    }
    
    private void unsubscribe(String topic, CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Log.d(TAG, "Unsubscribed from topic: " + topic);
                    callbackContext.success();
                } else {
                    Log.e(TAG, "Failed to unsubscribe from topic", task.getException());
                    callbackContext.error("Failed to unsubscribe: " + task.getException().getMessage());
                }
            });
    }
    
    // ==================== Token Deletion ====================
    
    private void unregister(CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().deleteToken()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Log.d(TAG, "FCM token deleted");
                    callbackContext.success();
                } else {
                    Log.e(TAG, "Failed to delete token", task.getException());
                    callbackContext.error("Failed to unregister: " + task.getException().getMessage());
                }
            });
    }
    
    // ==================== Channels (Android 8+) ====================
    
    private void createDefaultChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) cordova.getActivity()
                .getSystemService(Context.NOTIFICATION_SERVICE);
            
            // Create "default" channel (matches server's channel_id)
            NotificationChannel defaultChannel = new NotificationChannel(
                "default",
                "Default",
                NotificationManager.IMPORTANCE_HIGH  // HIGH = heads-up notification
            );
            defaultChannel.setDescription("Default notification channel");
            defaultChannel.enableVibration(true);
            defaultChannel.enableLights(true);
            nm.createNotificationChannel(defaultChannel);
            
            // Also create wizzopush_default as fallback
            NotificationChannel wizzopushChannel = new NotificationChannel(
                "wizzopush_default",
                "WizzoPush Default",
                NotificationManager.IMPORTANCE_HIGH
            );
            wizzopushChannel.setDescription("WizzoPush default notification channel");
            wizzopushChannel.enableVibration(true);
            wizzopushChannel.enableLights(true);
            nm.createNotificationChannel(wizzopushChannel);
        }
    }
    
    private void createChannel(JSONObject options, CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String id = options.getString("id");
                String name = options.optString("name", id);
                String description = options.optString("description", "");
                int importance = options.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT);

                NotificationChannel channel = new NotificationChannel(id, name, importance);
                channel.setDescription(description);

                if (options.has("sound")) {
                    String soundName = options.getString("sound");
                    Context ctx = cordova.getActivity().getApplicationContext();
                    int soundResId = ctx.getResources().getIdentifier(
                        soundName, "raw", ctx.getPackageName());
                    if (soundResId != 0) {
                        Uri soundUri = Uri.parse("android.resource://" + ctx.getPackageName() + "/" + soundResId);
                        android.media.AudioAttributes attr = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                        channel.setSound(soundUri, attr);
                        Log.d(TAG, "Channel sound set to: " + soundName + " (resId=" + soundResId + ")");
                    } else {
                        Log.w(TAG, "Sound resource not found: " + soundName);
                    }
                }

                if (options.has("vibration")) {
                    Object vibVal = options.get("vibration");
                    if (vibVal instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) vibVal;
                        long[] pattern = new long[arr.length()];
                        for (int i = 0; i < arr.length(); i++) {
                            pattern[i] = arr.getLong(i);
                        }
                        channel.enableVibration(true);
                        channel.setVibrationPattern(pattern);
                    } else {
                        channel.enableVibration(Boolean.parseBoolean(vibVal.toString()));
                    }
                }

                if (options.has("visibility")) {
                    channel.setLockscreenVisibility(options.getInt("visibility"));
                }

                if (options.has("badge")) {
                    channel.setShowBadge(options.getBoolean("badge"));
                }

                if (options.has("lightColor")) {
                    channel.enableLights(true);
                }

                NotificationManager nm = (NotificationManager) cordova.getActivity()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created: " + id);

                callbackContext.success();
            } catch (JSONException e) {
                Log.e(TAG, "createChannel error: " + e.getMessage());
                callbackContext.error("Invalid channel options: " + e.getMessage());
            }
        } else {
            callbackContext.success();
        }
    }
    
    private void deleteChannel(String channelId, CallbackContext callbackContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) cordova.getActivity()
                .getSystemService(Context.NOTIFICATION_SERVICE);
            nm.deleteNotificationChannel(channelId);
        }
        callbackContext.success();
    }
    
    private void setDefaultChannel(JSONObject options, CallbackContext callbackContext) {
        // Create or update the default channel
        createChannel(options, callbackContext);
    }
    
    private void listChannels(CallbackContext callbackContext) {
        JSONArray channels = new JSONArray();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) cordova.getActivity()
                .getSystemService(Context.NOTIFICATION_SERVICE);
            
            for (NotificationChannel channel : nm.getNotificationChannels()) {
                try {
                    JSONObject channelJson = new JSONObject();
                    channelJson.put("id", channel.getId());
                    channelJson.put("name", channel.getName());
                    channelJson.put("description", channel.getDescription());
                    channelJson.put("importance", channel.getImportance());
                    channels.put(channelJson);
                } catch (JSONException e) {
                    Log.e(TAG, "Error converting channel to JSON", e);
                }
            }
        }
        
        callbackContext.success(channels);
    }
    
    // ==================== Auto-init ====================
    
    private void isAutoInitEnabled(CallbackContext callbackContext) {
        boolean enabled = FirebaseMessaging.getInstance().isAutoInitEnabled();
        callbackContext.success(enabled ? "true" : "false");
    }
    
    private void setAutoInitEnabled(boolean enabled, CallbackContext callbackContext) {
        FirebaseMessaging.getInstance().setAutoInitEnabled(enabled);
        callbackContext.success();
    }
    
    // ==================== Diagnostics ====================
    
    private void getDiagnostics(CallbackContext callbackContext) {
        try {
            JSONObject diagnostics = new JSONObject();
            diagnostics.put("platform", "android");
            diagnostics.put("sdkVersion", Build.VERSION.SDK_INT);
            diagnostics.put("pluginVersion", "1.0.0");
            
            // Check notification permission
            boolean hasPermission;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission = ContextCompat.checkSelfPermission(
                    cordova.getActivity(), 
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;
            } else {
                hasPermission = NotificationManagerCompat.from(cordova.getActivity()).areNotificationsEnabled();
            }
            diagnostics.put("notificationPermission", hasPermission ? "granted" : "denied");
            
            // Get FCM token status
            diagnostics.put("hasMessageCallback", messageReceivedCallback != null);
            diagnostics.put("hasTokenCallback", tokenRefreshCallback != null);
            
            callbackContext.success(diagnostics);
        } catch (JSONException e) {
            callbackContext.error("Error getting diagnostics: " + e.getMessage());
        }
    }
    
    private void clearDiagnostics(CallbackContext callbackContext) {
        // Reserved for future diagnostic logging
        callbackContext.success();
    }
    
    // ==================== Firebase Analytics ====================
    
    private void logEvent(String eventName, JSONObject params, CallbackContext callbackContext) {
        try {
            Bundle bundle = new Bundle();
            
            if (params != null) {
                java.util.Iterator<String> keys = params.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = params.get(key);
                    
                    if (value instanceof String) {
                        bundle.putString(key, (String) value);
                    } else if (value instanceof Integer) {
                        bundle.putInt(key, (Integer) value);
                    } else if (value instanceof Long) {
                        bundle.putLong(key, (Long) value);
                    } else if (value instanceof Double) {
                        bundle.putDouble(key, (Double) value);
                    } else if (value instanceof Boolean) {
                        bundle.putBoolean(key, (Boolean) value);
                    } else {
                        bundle.putString(key, value.toString());
                    }
                }
            }
            
            if (analytics == null) { callbackContext.error(ANALYTICS_ABSENT); return; }
            analytics.logEvent(eventName, bundle);
            Log.d(TAG, "Logged event: " + eventName);
            callbackContext.success();
        } catch (Exception e) {
            Log.e(TAG, "Error logging event", e);
            callbackContext.error("Error logging event: " + e.getMessage());
        }
    }
    
    private void setAnalyticsCollectionEnabled(boolean enabled, CallbackContext callbackContext) {
        if (analytics == null) { callbackContext.error(ANALYTICS_ABSENT); return; }
        analytics.setCollectionEnabled(enabled);
        Log.d(TAG, "Analytics collection enabled: " + enabled);
        callbackContext.success();
    }
    
    private void setUserId(String userId, CallbackContext callbackContext) {
        if (analytics == null) { callbackContext.error(ANALYTICS_ABSENT); return; }
        analytics.setUserId(userId);
        Log.d(TAG, "Set user ID: " + userId);
        callbackContext.success();
    }
    
    private void setUserProperty(String name, String value, CallbackContext callbackContext) {
        if (analytics == null) { callbackContext.error(ANALYTICS_ABSENT); return; }
        analytics.setUserProperty(name, value);
        Log.d(TAG, "Set user property: " + name + " = " + value);
        callbackContext.success();
    }
    
    private void setScreenName(String screenName, CallbackContext callbackContext) {
        if (analytics == null) { callbackContext.error(ANALYTICS_ABSENT); return; }
        analytics.setScreenName(screenName);
        Log.d(TAG, "Set screen name: " + screenName);
        callbackContext.success();
    }
    
    // ==================== Google Sign-In ====================
    
    private void authenticateUserWithGoogle(String webClientId, CallbackContext callbackContext) {
        googleSignInCallback = callbackContext;
        
        cordova.getActivity().runOnUiThread(() -> {
            try {
                GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build();
                
                googleSignInClient = GoogleSignIn.getClient(cordova.getActivity(), gso);
                
                // Sign out first to ensure account picker is shown
                googleSignInClient.signOut().addOnCompleteListener(task -> {
                    Intent signInIntent = googleSignInClient.getSignInIntent();
                    cordova.startActivityForResult(WizzoPushPlugin.this, signInIntent, GOOGLE_SIGN_IN_REQUEST_CODE);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting Google Sign-In", e);
                callbackContext.error("Error starting Google Sign-In: " + e.getMessage());
                googleSignInCallback = null;
            }
        });
    }
    
    private void signOutGoogle(CallbackContext callbackContext) {
        if (googleSignInClient != null) {
            googleSignInClient.signOut().addOnCompleteListener(task -> {
                Log.d(TAG, "Google Sign-Out completed");
                callbackContext.success();
            });
        } else {
            callbackContext.success();
        }
    }
    
    // ==================== Microsoft Sign-In (MSAL) ====================

    // The MSAL code itself lives in WizzoPushMsal, so that nothing here names an MSAL type
    // and an app without Microsoft sign-in can leave the SDK out of its build entirely.
    // When it is out, the call below throws NoClassDefFoundError and the user gets a plain
    // error instead of a crash.
    private void authenticateUserWithMicrosoft(CallbackContext callbackContext) {
        final CallbackContext callback = callbackContext;
        try {
            WizzoPushMsal.signIn(cordova.getActivity(), new WizzoPushMsal.Result() {
                @Override
                public void onCredential(JSONObject credential) {
                    callback.success(credential);
                }

                @Override
                public void onError(String message) {
                    callback.error(message);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Microsoft Sign-In is not available in this build", t);
            callbackContext.error(MSAL_ABSENT);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == GOOGLE_SIGN_IN_REQUEST_CODE) {
            try {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                GoogleSignInAccount account = task.getResult(ApiException.class);
                
                if (account != null && googleSignInCallback != null) {
                    JSONObject credential = new JSONObject();
                    credential.put("idToken", account.getIdToken());
                    credential.put("accessToken", ""); // Google Sign-In doesn't provide access token directly
                    credential.put("email", account.getEmail());
                    credential.put("displayName", account.getDisplayName());
                    credential.put("photoUrl", account.getPhotoUrl() != null ? account.getPhotoUrl().toString() : "");
                    credential.put("id", account.getId());
                    
                    Log.d(TAG, "Google Sign-In successful: " + account.getEmail());
                    googleSignInCallback.success(credential);
                }
            } catch (ApiException e) {
                Log.e(TAG, "Google Sign-In failed with code: " + e.getStatusCode(), e);
                if (googleSignInCallback != null) {
                    googleSignInCallback.error("Google Sign-In failed: " + e.getStatusCode() + " - " + e.getMessage());
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error creating credential JSON", e);
                if (googleSignInCallback != null) {
                    googleSignInCallback.error("Error creating credential: " + e.getMessage());
                }
            }
            googleSignInCallback = null;
        }
    }
}
