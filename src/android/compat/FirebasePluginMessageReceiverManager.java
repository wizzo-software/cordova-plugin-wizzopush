package org.apache.cordova.firebase;

import android.util.Log;

import com.google.firebase.messaging.RemoteMessage;

import coffee.sunday.wizzopush.WizzoPushMessageReceiver;
import coffee.sunday.wizzopush.WizzoPushMessageReceiverManager;

/**
 * Compatibility class for plugins that depend on FirebaseX.
 * Wraps WizzoPushMessageReceiverManager with the same package path as FirebaseX.
 * 
 * This allows plugins like VoIPPlugin to work without modification.
 */
public class FirebasePluginMessageReceiverManager {
    
    private static final String TAG = "FirebasePluginMsgRecMgr";
    
    /**
     * Register a FirebasePluginMessageReceiver (which extends WizzoPushMessageReceiver).
     * 
     * @param receiver The receiver to register
     */
    public static void register(FirebasePluginMessageReceiver receiver) {
        WizzoPushMessageReceiverManager.register(receiver);
        Log.d(TAG, "Registered FirebaseX-compatible receiver: " + receiver.getClass().getSimpleName());
    }
    
    /**
     * Unregister a FirebasePluginMessageReceiver.
     * 
     * @param receiver The receiver to unregister
     */
    public static void unregister(FirebasePluginMessageReceiver receiver) {
        WizzoPushMessageReceiverManager.unregister(receiver);
    }
    
    /**
     * Dispatch message to all registered receivers.
     * This is called internally by WizzoPush.
     */
    public static boolean onMessageReceived(RemoteMessage remoteMessage) {
        return WizzoPushMessageReceiverManager.onMessageReceived(remoteMessage);
    }
}
