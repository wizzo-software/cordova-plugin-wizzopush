package coffee.sunday.wizzopush;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

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
    private static final String DEFAULT_CHANNEL_ID = "default";
    
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

        if (plugin != null) {
            try {
                if (hasNotification) messageJson.put("messageType", "notification");
                if (hasData) messageJson.put("messageType", "data");
            } catch (JSONException e) {
                Log.e(TAG, "Error adding messageType", e);
            }
            plugin.onMessageReceived(messageJson);
        } else {
            if (!hasNotification && hasData) {
                showNotificationFromData(remoteMessage.getData());
            }
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
    
    /**
     * Show a notification when a data-only message arrives and app is in background
     */
    private void showNotificationFromData(Map<String, String> data) {
        String title = data.get("title");
        String body = data.get("body");
        
        if (title == null && body == null) {
            // No notification content
            return;
        }
        
        if (title == null) title = "";
        if (body == null) body = "";
        
        // Create intent for notification tap
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            Log.e(TAG, "Could not get launch intent");
            return;
        }
        
        // Add all data to intent extras for retrieval when app opens
        for (Map.Entry<String, String> entry : data.entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            (int) System.currentTimeMillis(), // Unique request code
            intent, 
            flags
        );
        
        // Get default notification sound
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        
        // Build notification
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(getApplicationInfo().icon) // Use app icon
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        
        // Add image if present
        String imageUrl = data.get("image");
        if (imageUrl != null && !imageUrl.isEmpty()) {
            // For simplicity, we're not loading the image here
            // A more complete implementation would use Glide/Picasso
        }
        
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Create channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = notificationManager.getNotificationChannel(DEFAULT_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                    DEFAULT_CHANNEL_ID,
                    "Default",
                    NotificationManager.IMPORTANCE_DEFAULT
                );
                notificationManager.createNotificationChannel(channel);
            }
        }
        
        // Use messageId or timestamp for notification ID
        String messageId = data.get("messageId");
        int notificationId = messageId != null ? messageId.hashCode() : (int) System.currentTimeMillis();
        
        notificationManager.notify(notificationId, notificationBuilder.build());
    }
}
