package coffee.sunday.wizzopush;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Draws a notification for a DATA-ONLY push the way a messaging app does:
 * the sender's picture in a circle next to the text, the app's own small icon in the corner,
 * and on Android 11+ a real conversation (MessagingStyle + Person + a long-lived shortcut),
 * so several messages of the same conversation stack in one card and the phone puts it in
 * the "Conversations" section.
 *
 * Why data-only: an FCM "notification" message is drawn by the Firebase SDK itself while
 * the app is in the background, and the SDK never shows a sender picture. A data-only
 * message always reaches {@link WizzoFirebaseMessagingService}, which calls {@link #show}
 * unless the app is on screen (then the JS layer gets the message and shows its own UI).
 *
 * Keys read from the data map (all strings, all optional except title/body):
 *   title            the notification title (used as the message text when body is empty)
 *   body             the message text
 *   icon             https URL of the sender's picture (PNG/JPEG, square). Circled, shown big.
 *   image            https URL of a picture to show expanded (BigPictureStyle) when the
 *                    push is not a conversation
 *   sender_name      the name of who sent it; shown bold. With conversation_id this makes
 *                    the push a conversation
 *   sender_key       a stable key for the sender (the Person key); defaults to sender_name
 *   conversation_id  the thread: pushes with the same id append to one card and share a
 *                    shortcut. Without it every push is its own card
 *   recipient_name   the reader's own name (the "me" of the thread); defaults to the app name
 *   channel_id       the notification channel to post on; defaults to "default"
 *   notification_id  an explicit integer id; defaults to a hash of conversation_id or messageId
 *   url, and anything else   passed through untouched as intent extras, so the JS tap
 *                    payload carries the whole data map
 */
public final class WizzoPushNotifier {

    private static final String TAG = "WizzoPushNotifier";
    private static final String DEFAULT_CHANNEL_ID = "default";
    private static final String SHORTCUT_PREFIX = "wizzopush_conv_";
    private static final String ICON_CACHE_DIR = "wizzopush_icons";
    private static final int ICON_PX = 256;
    private static final long ICON_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int NET_TIMEOUT_MS = 6000;

    /** True while the Cordova activity is on screen (set by WizzoPushPlugin onResume/onPause). */
    private static volatile boolean foreground = false;

    private WizzoPushNotifier() {}

    public static void setForeground(boolean onScreen) {
        foreground = onScreen;
    }

    public static boolean isForeground() {
        return foreground;
    }

    /** The id the notification of a conversation is posted under (also what cancel uses). */
    public static int notificationIdFor(Map<String, String> data) {
        String explicit = data.get("notification_id");
        if (explicit != null) {
            try { return Integer.parseInt(explicit.trim()); } catch (NumberFormatException ignored) {}
        }
        String conversationId = data.get("conversation_id");
        if (conversationId != null && !conversationId.isEmpty()) {
            return ("conv:" + conversationId).hashCode();
        }
        String messageId = data.get("messageId");
        if (messageId == null) messageId = data.get("message_id");
        return messageId != null ? messageId.hashCode() : (int) System.currentTimeMillis();
    }

    public static void cancelConversation(Context ctx, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        NotificationManagerCompat.from(ctx).cancel(("conv:" + conversationId).hashCode());
    }

    /**
     * Build and post the notification. Safe to call from the FCM service thread (it downloads
     * the pictures with short timeouts). Returns false when there was nothing to show.
     */
    public static boolean show(Context ctx, Map<String, String> data) {
        String title = data.get("title");
        String body = data.get("body");
        if (isEmpty(title) && isEmpty(body)) return false;
        if (title == null) title = "";
        if (body == null) body = "";

        try {
            return post(ctx, data, title, body);
        } catch (Throwable t) {
            // Never let a picture or a shortcut problem swallow the notification: fall back
            // to a plain card with the text.
            Log.e(TAG, "Rich notification failed, posting a plain one", t);
            try {
                NotificationCompat.Builder plain = baseBuilder(ctx, data, title, body);
                NotificationManagerCompat.from(ctx).notify(notificationIdFor(data), plain.build());
                return true;
            } catch (Throwable inner) {
                Log.e(TAG, "Plain notification failed too", inner);
                return false;
            }
        }
    }

    private static boolean post(Context ctx, Map<String, String> data, String title, String body) {
        String senderName = data.get("sender_name");
        String conversationId = data.get("conversation_id");
        String iconUrl = data.get("icon");
        String imageUrl = data.get("image");
        int notificationId = notificationIdFor(data);

        Bitmap avatar = isEmpty(iconUrl) ? null : circled(loadBitmap(ctx, iconUrl, ICON_PX));
        NotificationCompat.Builder builder = baseBuilder(ctx, data, title, body);

        if (avatar != null) builder.setLargeIcon(avatar);

        boolean conversation = !isEmpty(senderName);
        if (conversation) {
            String senderKey = data.get("sender_key");
            if (isEmpty(senderKey)) senderKey = senderName;
            Person.Builder sender = new Person.Builder().setName(senderName).setKey(senderKey);
            if (avatar != null) sender.setIcon(IconCompat.createWithBitmap(avatar));
            Person senderPerson = sender.build();

            String me = data.get("recipient_name");
            if (isEmpty(me)) me = appLabel(ctx);
            Person mePerson = new Person.Builder().setName(me).build();

            NotificationCompat.MessagingStyle style = null;
            if (!isEmpty(conversationId)) {
                style = existingThread(ctx, notificationId);
            }
            if (style == null) style = new NotificationCompat.MessagingStyle(mePerson);
            style.setGroupConversation(false);

            String text = isEmpty(body) ? title : body;
            style.addMessage(text, System.currentTimeMillis(), senderPerson);
            builder.setStyle(style);
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE);
            builder.addPerson(senderPerson);
            if (!isEmpty(conversationId)) {
                String shortcutId = publishShortcut(ctx, conversationId, senderPerson, avatar, data);
                if (shortcutId != null) builder.setShortcutId(shortcutId);
            }
        } else if (!isEmpty(imageUrl)) {
            Bitmap big = loadBitmap(ctx, imageUrl, 1024);
            if (big != null) {
                builder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(big)
                    .bigLargeIcon((Bitmap) null)
                    .setSummaryText(body));
            }
        }

        NotificationManagerCompat.from(ctx).notify(notificationId, builder.build());
        return true;
    }

    // ---------------------------------------------------------------- builder

    private static NotificationCompat.Builder baseBuilder(Context ctx, Map<String, String> data,
                                                          String title, String body) {
        String channelId = data.get("channel_id");
        if (isEmpty(channelId)) channelId = DEFAULT_CHANNEL_ID;
        ensureChannel(ctx, channelId);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(smallIcon(ctx))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(ctx, data, notificationIdFor(data)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        if (!isEmpty(body)) b.setStyle(new NotificationCompat.BigTextStyle().bigText(body));

        int color = accentColor(ctx);
        if (color != 0) b.setColor(color);
        return b;
    }

    private static PendingIntent tapIntent(Context ctx, Map<String, String> data, int requestCode) {
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (intent == null) intent = new Intent(Intent.ACTION_MAIN);
        for (Map.Entry<String, String> entry : data.entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, requestCode, intent, flags);
    }

    /**
     * The small (status bar) icon: the app's fcm_push_icon drawable when it has one, then the
     * Firebase default_notification_icon meta-data, then the launcher icon.
     */
    private static int smallIcon(Context ctx) {
        int id = ctx.getResources().getIdentifier("fcm_push_icon", "drawable", ctx.getPackageName());
        if (id != 0) return id;
        Bundle meta = metaData(ctx);
        if (meta != null) {
            id = meta.getInt("com.google.firebase.messaging.default_notification_icon", 0);
            if (id != 0) return id;
        }
        return ctx.getApplicationInfo().icon;
    }

    private static int accentColor(Context ctx) {
        Bundle meta = metaData(ctx);
        if (meta == null) return 0;
        int res = meta.getInt("com.google.firebase.messaging.default_notification_color", 0);
        if (res == 0) return 0;
        try { return ContextCompat.getColor(ctx, res); } catch (Exception e) { return 0; }
    }

    private static Bundle metaData(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                .getApplicationInfo(ctx.getPackageName(), PackageManager.GET_META_DATA);
            return ai.metaData;
        } catch (Exception e) {
            return null;
        }
    }

    private static String appLabel(Context ctx) {
        try {
            CharSequence label = ctx.getPackageManager().getApplicationLabel(ctx.getApplicationInfo());
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception ignored) {}
        return "Me";
    }

    private static void ensureChannel(Context ctx, String channelId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(channelId) != null) return;
        NotificationChannel channel = new NotificationChannel(
            channelId,
            DEFAULT_CHANNEL_ID.equals(channelId) ? "Default" : channelId,
            NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        channel.enableLights(true);
        nm.createNotificationChannel(channel);
    }

    // ---------------------------------------------------------------- conversation thread

    /** The MessagingStyle already on screen for this id, so the new message joins the card. */
    private static NotificationCompat.MessagingStyle existingThread(Context ctx, int notificationId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return null;
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() != notificationId) continue;
                Notification n = sbn.getNotification();
                return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the existing thread", e);
        }
        return null;
    }

    private static String publishShortcut(Context ctx, String conversationId, Person sender,
                                          Bitmap avatar, Map<String, String> data) {
        try {
            String shortcutId = SHORTCUT_PREFIX + conversationId;
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (intent == null) return null;
            intent.setAction(Intent.ACTION_VIEW);
            for (Map.Entry<String, String> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
            ShortcutInfoCompat.Builder sb = new ShortcutInfoCompat.Builder(ctx, shortcutId)
                .setShortLabel(sender.getName() != null ? sender.getName() : conversationId)
                .setLongLived(true)
                .setPerson(sender)
                .setIntent(intent);
            if (avatar != null) sb.setIcon(IconCompat.createWithBitmap(avatar));
            ShortcutManagerCompat.pushDynamicShortcut(ctx, sb.build());
            return shortcutId;
        } catch (Throwable t) {
            // A launcher without shortcut support, or too many shortcuts: the card still
            // shows the avatar, it just does not get the conversation section.
            Log.w(TAG, "Shortcut not published", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- pictures

    /** Download (or take from the cache) and decode a picture, scaled to about maxPx. */
    private static Bitmap loadBitmap(Context ctx, String url, int maxPx) {
        File file = cachedFile(ctx, url);
        if (file == null) return null;
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > ICON_CACHE_TTL_MS) {
            if (!download(url, file)) return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) {
                file.delete();
                return null;
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "Could not decode " + url, t);
            return null;
        }
    }

    private static boolean download(String url, File into) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(NET_TIMEOUT_MS);
            conn.setReadTimeout(NET_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Picture " + url + " answered " + conn.getResponseCode());
                return false;
            }
            File tmp = new File(into.getAbsolutePath() + ".part");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(into)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Could not download " + url, t);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static File cachedFile(Context ctx, String url) {
        try {
            File dir = new File(ctx.getCacheDir(), ICON_CACHE_DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return new File(dir, hex.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Center-crop to a square and cut a circle, the shape every messaging app uses. */
    private static Bitmap circled(Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        Bitmap square = Bitmap.createBitmap(src, x, y, size, size);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        if (square != src) square.recycle();
        return out;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
