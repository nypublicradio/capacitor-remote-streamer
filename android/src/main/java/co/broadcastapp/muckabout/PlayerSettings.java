package co.broadcastapp.muckabout;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.getcapacitor.CapConfig;
import com.getcapacitor.PluginConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Host-controlled branding and settings from {@code capacitor.config} →
 * {@code plugins.RemoteStreamer}.
 *
 * <p>Read with {@link CapConfig#loadDefault} rather than through the plugin bridge so they
 * also apply when playback starts without the WebView (e.g. from Android Auto).
 */
final class PlayerSettings {

    static final String DEFAULT_USER_AGENT = "{appName}/{appVersion} (Android {osVersion}; {deviceModel})";
    static final String DEFAULT_CHANNEL_NAME = "Playback";

    private static final String TAG = "RemoteStreamer";

    /** Notification/Now Playing title used when the current item has none. */
    @NonNull
    final String defaultTitle;

    /** User-Agent header sent with every stream request. */
    @NonNull
    final String userAgent;

    /** Drawable for the notification's small (status bar) icon. */
    final int smallIconResId;

    /** Notification accent color, or null for the system default. */
    @Nullable
    final Integer iconColor;

    /** Name of the notification channel shown in system settings. */
    @NonNull
    final String channelName;

    private PlayerSettings(@NonNull String defaultTitle, @NonNull String userAgent, int smallIconResId, @Nullable Integer iconColor, @NonNull String channelName) {
        this.defaultTitle = defaultTitle;
        this.userAgent = userAgent;
        this.smallIconResId = smallIconResId;
        this.iconColor = iconColor;
        this.channelName = channelName;
    }

    @NonNull
    static PlayerSettings load(@NonNull Context context) {
        PluginConfig config = CapConfig.loadDefault(context).getPluginConfiguration("RemoteStreamer");

        String template = nonEmpty(config.getString("userAgent.android"));
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("appName", appName(context));
        tokens.put("appVersion", appVersion(context));
        tokens.put("osVersion", Build.VERSION.RELEASE);
        tokens.put("deviceModel", Build.MODEL);
        String userAgent = renderUserAgent(template != null ? template : DEFAULT_USER_AGENT, tokens);

        String defaultTitle = nonEmpty(config.getString("defaultTitle"));
        String channelName = nonEmpty(config.getString("android.notification.channelName"));

        return new PlayerSettings(
            defaultTitle != null ? defaultTitle : "",
            userAgent,
            resolveSmallIcon(context, nonEmpty(config.getString("android.notification.smallIcon"))),
            parseColor(nonEmpty(config.getString("android.notification.iconColor"))),
            channelName != null ? channelName : DEFAULT_CHANNEL_NAME
        );
    }

    /** Replaces {@code {token}} placeholders in a User-Agent template. */
    @NonNull
    static String renderUserAgent(@NonNull String template, @NonNull Map<String, String> tokens) {
        String result = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace("{" + token.getKey() + "}", token.getValue());
        }
        return result;
    }

    /**
     * Treats empty strings and the literal "undefined"/"null" (an unset env var in
     * capacitor.config.ts) as missing.
     */
    @Nullable
    static String nonEmpty(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equals("undefined") || trimmed.equals("null")) return null;
        return trimmed;
    }

    /** Looks up a drawable by name in the host app, falling back to the plugin's neutral icon. */
    private static int resolveSmallIcon(@NonNull Context context, @Nullable String name) {
        if (name != null) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id != 0) return id;
            Log.w(TAG, "Notification icon '" + name + "' not found in res/drawable; using default");
        }
        return R.drawable.remote_streamer_default_icon;
    }

    @Nullable
    private static Integer parseColor(@Nullable String value) {
        if (value == null) return null;
        try {
            return Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid notification iconColor '" + value + "'");
            return null;
        }
    }

    @NonNull
    private static String appName(@NonNull Context context) {
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        return label != null ? label.toString() : "App";
    }

    @NonNull
    private static String appVersion(@NonNull Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name != null ? name : "1.0";
        } catch (Exception e) {
            Log.e(TAG, "Failed to get version name", e);
            return "1.0";
        }
    }
}
