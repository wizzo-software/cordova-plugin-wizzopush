package coffee.sunday.wizzopush;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * WizzoPush Firebase Messaging Service
 * Handles incoming FCM messages and token refresh
 */
public class WizzoFirebaseMessagingService extends FirebaseMessagingService {
    
    private static final String TAG = "WizzoPushFMS";
    
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token: " + token);
        
        // Notify the plugin about the new token
        WizzoPushPlugin plugin = WizzoPushPlugin.getInstance();
        if (plugin != null) {
            plugin.onNewToken(token);
        }
    }
    
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Log.d(TAG, "Message received from: " + remoteMessage.getFrom());
        
        // First, dispatch to registered custom receivers (VoIP, etc.)
        // If any receiver handles the message, stop processing
        if (WizzoPushMessageReceiverManager.onMessageReceived(remoteMessage)) {
            Log.d(TAG, "Message handled by custom receiver");
            return;
        }
        
        // Convert RemoteMessage to JSONObject for JS
        JSONObject messageJson = remoteMessageToJson(remoteMessage);
        
        WizzoPushPlugin plugin = WizzoPushPlugin.getInstance();
        boolean hasNotification = remoteMessage.getNotification() != null;
        boolean hasData = remoteMessage.getData().size() > 0;

        if (hasNotification) {
            Log.d(TAG, "Notification body: " + remoteMessage.getNotification().getBody());
        }
        if (hasData) {
            Log.d(TAG, "Data payload: " + remoteMessage.getData());
        }

        // A data-only message while the app is not on screen: draw it ourselves, with the
        // sender's picture and the conversation thread (WizzoPushNotifier). The Firebase SDK
        // draws "notification" messages on its own in the background, so those are left to it.
        // On screen, the JS layer gets the message and shows its own in-app UI.
        boolean drawnHere = false;
        if (!hasNotification && hasData && !WizzoPushNotifier.isForeground()) {
            drawnHere = WizzoPushNotifier.show(this, remoteMessage.getData());
        }

        if (plugin != null) {
            try {
                if (hasNotification) messageJson.put("messageType", "notification");
                if (hasData) messageJson.put("messageType", "data");
                if (drawnHere) messageJson.put("shownNatively", true);
            } catch (JSONException e) {
                Log.e(TAG, "Error adding messageType", e);
            }
            plugin.onMessageReceived(messageJson);
        }
    }
    
    /**
     * Convert RemoteMessage to JSONObject matching the expected payload structure
     */
    private JSONObject remoteMessageToJson(RemoteMessage remoteMessage) {
        JSONObject json = new JSONObject();
        
        try {
            // Add notification data if present
            if (remoteMessage.getNotification() != null) {
                RemoteMessage.Notification notification = remoteMessage.getNotification();
                
                if (notification.getTitle() != null) {
                    json.put("title", notification.getTitle());
                }
                if (notification.getBody() != null) {
                    json.put("body", notification.getBody());
                }
                if (notification.getIcon() != null) {
                    json.put("icon", notification.getIcon());
                }
                if (notification.getImageUrl() != null) {
                    json.put("image", notification.getImageUrl().toString());
                }
                if (notification.getClickAction() != null) {
                    json.put("click_action", notification.getClickAction());
                }
            }
            
            // Add all data payload fields
            Map<String, String> data = remoteMessage.getData();
            for (Map.Entry<String, String> entry : data.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            
            // Add metadata
            json.put("from", remoteMessage.getFrom());
            if (remoteMessage.getMessageId() != null) {
                json.put("messageId", remoteMessage.getMessageId());
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Error converting message to JSON", e);
        }
        
        return json;
    }
}
