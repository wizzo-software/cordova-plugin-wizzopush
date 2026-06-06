package coffee.sunday.wizzopush;

import android.os.Bundle;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Base class for custom message receivers.
 * Plugins can extend this to intercept FCM messages before they reach WizzoPush.
 * 
 * This is compatible with FirebaseX's FirebasePluginMessageReceiver for easy migration.
 */
public abstract class WizzoPushMessageReceiver {
    
    /**
     * Called when a message is received.
     * 
     * @param remoteMessage The FCM message
     * @return true if message was handled and should not be processed further,
     *         false to let WizzoPush continue processing
     */
    public abstract boolean onMessageReceived(RemoteMessage remoteMessage);
    
    /**
     * Called to send a message (for outgoing messages if needed)
     * 
     * @param bundle Message data
     * @return true if message was sent successfully
     */
    public boolean sendMessage(Bundle bundle) {
        return false;
    }
}
