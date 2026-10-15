package co.broadcastapp.muckabout.library;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Finds the app's {@link MediaLibraryProvider} through manifest meta-data. */
public final class ProviderLoader {

    /** Application-level meta-data key naming the provider class. */
    public static final String META_DATA_KEY = "co.broadcastapp.muckabout.MEDIA_LIBRARY_PROVIDER";

    private static final String TAG = "RemoteStreamer";

    private ProviderLoader() {}

    /** Creates the declared provider, or returns null if none is declared or it fails to load. */
    @Nullable
    public static MediaLibraryProvider load(@NonNull Context context) {
        String className = readClassName(context);
        if (className == null || className.isEmpty()) {
            return null;
        }
        try {
            Class<?> type = Class.forName(className);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof MediaLibraryProvider)) {
                Log.e(TAG, className + " does not implement MediaLibraryProvider");
                return null;
            }
            MediaLibraryProvider provider = (MediaLibraryProvider) instance;
            provider.onCreate(context.getApplicationContext());
            Log.d(TAG, "Loaded media library provider " + className);
            return provider;
        } catch (Exception e) {
            Log.e(TAG, "Could not load media library provider " + className, e);
            return null;
        }
    }

    @Nullable
    private static String readClassName(@NonNull Context context) {
        try {
            ApplicationInfo info = context
                .getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = info.metaData;
            return metaData != null ? metaData.getString(META_DATA_KEY) : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
