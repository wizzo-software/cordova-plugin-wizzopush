package coffee.sunday.wizzopush;

import android.app.Activity;
import android.util.Log;

import com.microsoft.identity.client.AcquireTokenParameters;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.Prompt;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.exception.MsalException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every reference to MSAL in this plugin lives in this one class, for the same reason
 * WizzoPushAnalytics exists: an app with no Microsoft sign-in can drop
 * com.microsoft.identity.client:msal from its runtime classpath and keep push.
 *
 * MSAL is worth dropping when it is unused. It is not only weight (kotlin stdlib,
 * coroutines, datastore, nimbus-jose-jwt, moshi, okio, gson, httpcore5): it also pulls in
 * com.yubico.yubikit, which merges android.permission.NFC and a usb.host feature into the
 * manifest of an app that has never heard of a security key.
 *
 * The signature deliberately says nothing about MSAL, so WizzoPushPlugin never names an
 * MSAL type and never loads one unless someone actually calls authenticateUserWithMicrosoft.
 */
class WizzoPushMsal {

    private static final String TAG = "WizzoPush";
    private static final String[] SCOPES = {"User.Read", "openid", "profile", "email"};

    /** How a sign-in ends. Exactly one method is called, exactly once. */
    interface Result {
        void onCredential(JSONObject credential);
        void onError(String message);
    }

    private static IMultipleAccountPublicClientApplication msalApp;

    private WizzoPushMsal() {}

    /**
     * Start an interactive Microsoft sign-in. Safe to call on any thread; the MSAL call
     * itself is moved to the UI thread, which is where it was before this class existed.
     */
    static void signIn(final Activity activity, final Result result) {
        if (msalApp != null) {
            acquireToken(activity, result);
            return;
        }
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            activity.getApplicationContext(),
            activity.getResources().getIdentifier(
                "msal_auth_config", "raw", activity.getPackageName()),
            new IPublicClientApplication.IMultipleAccountApplicationCreatedListener() {
                @Override
                public void onCreated(IMultipleAccountPublicClientApplication application) {
                    msalApp = application;
                    Log.d(TAG, "MSAL initialized successfully");
                    acquireToken(activity, result);
                }

                @Override
                public void onError(MsalException exception) {
                    Log.e(TAG, "MSAL initialization failed", exception);
                    result.onError("MSAL init failed: " + exception.getMessage());
                }
            }
        );
    }

    private static void acquireToken(final Activity activity, final Result result) {
        activity.runOnUiThread(() -> {
            try {
                AcquireTokenParameters.Builder params = new AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(java.util.Arrays.asList(SCOPES))
                    .withPrompt(Prompt.SELECT_ACCOUNT)
                    .withCallback(new AuthenticationCallback() {
                        @Override
                        public void onSuccess(IAuthenticationResult authResult) {
                            try {
                                IAccount account = authResult.getAccount();
                                JSONObject credential = new JSONObject();
                                credential.put("accessToken", authResult.getAccessToken());
                                credential.put("idToken", account.getIdToken() != null ? account.getIdToken() : "");
                                credential.put("email", account.getUsername() != null ? account.getUsername() : "");
                                credential.put("displayName", "");
                                credential.put("uid", account.getId() != null ? account.getId() : "");
                                credential.put("authMethod", "msal");

                                // Extract display name from claims if available
                                java.util.Map<String, ?> claims = account.getClaims();
                                if (claims != null && claims.containsKey("name")) {
                                    credential.put("displayName", String.valueOf(claims.get("name")));
                                }

                                Log.d(TAG, "Microsoft MSAL Sign-In successful: " + account.getUsername());
                                result.onCredential(credential);
                            } catch (JSONException e) {
                                Log.e(TAG, "Error creating credential JSON", e);
                                result.onError("Error creating credential: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onError(MsalException exception) {
                            Log.e(TAG, "Microsoft MSAL Sign-In failed", exception);
                            result.onError("Microsoft Sign-In failed: " + exception.getMessage());
                        }

                        @Override
                        public void onCancel() {
                            Log.d(TAG, "Microsoft MSAL Sign-In cancelled");
                            result.onError("Microsoft Sign-In cancelled");
                        }
                    });

                msalApp.acquireToken(params.build());

            } catch (Exception e) {
                Log.e(TAG, "Error starting Microsoft MSAL Sign-In", e);
                result.onError("Error starting Microsoft Sign-In: " + e.getMessage());
            }
        });
    }
}
