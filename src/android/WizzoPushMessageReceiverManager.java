package coffee.sunday.wizzopush;

import android.util.Log;

import com.google.firebase.messaging.RemoteMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages custom message receivers for WizzoPush.
 * Plugins can register receivers to intercept FCM messages.
 * 
 * This is compatible with FirebaseX's FirebasePluginMessageReceiverManager for easy migration.
 */
public class WizzoPushMessageReceiverManager {
    
    private static final String TAG = "WizzoPushReceiverMgr";
    private static final List<WizzoPushMessageReceiver> receivers = new ArrayList<>();
    
    /**
     * Register a custom message receiver.
     * Receivers are called in order of registration.
     * 
     * @param receiver The receiver to register
     */
    public static void register(WizzoPushMessageReceiver receiver) {
        if (receiver != null && !receivers.contains(receiver)) {
            receivers.add(receiver);
            Log.d(TAG, "Registered receiver: " + receiver.getClass().getSimpleName());
        }
    }
    
    /**
     * Unregister a custom message receiver.
     * 
     * @param receiver The receiver to unregister
     */
    public static void unregister(WizzoPushMessageReceiver receiver) {
        if (receiver != null) {
            receivers.remove(receiver);
            Log.d(TAG, "Unregistered receiver: " + receiver.getClass().getSimpleName());
        }
    }
    
    /**
     * Clear all registered receivers.
     */
    public static void clearAll() {
        receivers.clear();
        Log.d(TAG, "All receivers cleared");
    }
    
    /**
     * Dispatch a message to all registered receivers.
     * 
     * @param remoteMessage The FCM message
     * @return true if any receiver handled the message
     */
    public static boolean onMessageReceived(RemoteMessage remoteMessage) {
        for (WizzoPushMessageReceiver receiver : receivers) {
            try {
                if (receiver.onMessageReceived(remoteMessage)) {
                    Log.d(TAG, "Message handled by: " + receiver.getClass().getSimpleName());
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in receiver " + receiver.getClass().getSimpleName(), e);
            }
        }
        return false;
    }
    
    /**
     * Get the number of registered receivers.
     */
    public static int getReceiverCount() {
        return receivers.size();
    }
}
