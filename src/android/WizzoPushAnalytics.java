package coffee.sunday.wizzopush;

import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Every reference to firebase-analytics in this plugin lives in this one class, and that is
 * the whole point of it.
 *
 * Some apps that use WizzoPush have no analytics to send and no advertising id to read, but
 * firebase-analytics merges com.google.android.gms.permission.AD_ID and the ACCESS_ADSERVICES
 * pair into their manifest all the same, which forces an advertising-id declaration in Play's
 * Data safety form and the entire Ads questionnaire. Such an app can now drop the artifact
 * from its runtime classpath (see README, "Building without analytics or MSAL"). When it
 * does, loading THIS class is the only thing that fails, WizzoPushPlugin catches that and
 * carries on, and push keeps working exactly as before.
 *
 * So: no other file in the plugin may touch com.google.firebase.analytics. The moment one
 * does, that promise quietly stops being true and those apps crash on startup instead.
 */
class WizzoPushAnalytics {

    private final FirebaseAnalytics firebaseAnalytics;

    private WizzoPushAnalytics(Context context) {
        firebaseAnalytics = FirebaseAnalytics.getInstance(context);
    }

    /**
     * The instance, or null when firebase-analytics is not part of this build.
     * Never throws: the caller is expected to treat null as "analytics is off".
     */
    static WizzoPushAnalytics createOrNull(Context context) {
        try {
            return new WizzoPushAnalytics(context);
        } catch (Throwable t) {
            // NoClassDefFoundError (artifact excluded) or anything Firebase itself throws.
            return null;
        }
    }

    void logEvent(String eventName, Bundle params) {
        firebaseAnalytics.logEvent(eventName, params);
    }

    void setCollectionEnabled(boolean enabled) {
        firebaseAnalytics.setAnalyticsCollectionEnabled(enabled);
    }

    void setUserId(String userId) {
        firebaseAnalytics.setUserId(userId);
    }

    void setUserProperty(String name, String value) {
        firebaseAnalytics.setUserProperty(name, value);
    }

    void setScreenName(String screenName) {
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName);
        bundle.putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName);
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle);
    }
}
