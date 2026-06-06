package org.apache.cordova.firebase;

import coffee.sunday.wizzopush.WizzoPushMessageReceiver;

/**
 * Compatibility class for plugins that depend on FirebaseX.
 * Extends WizzoPushMessageReceiver with the same package path as FirebaseX.
 * 
 * This allows plugins like VoIPPlugin to work without modification.
 */
public abstract class FirebasePluginMessageReceiver extends WizzoPushMessageReceiver {
    // All functionality is inherited from WizzoPushMessageReceiver
}
