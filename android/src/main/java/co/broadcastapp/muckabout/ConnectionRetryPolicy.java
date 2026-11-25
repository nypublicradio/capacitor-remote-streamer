package co.broadcastapp.muckabout;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import java.io.IOException;

/** Keeps retrying loads the network cut off: failing would reload the stream URL, which starts a new ad session. */
@OptIn(markerClass = UnstableApi.class)
class ConnectionRetryPolicy extends DefaultLoadErrorHandlingPolicy {

    @Override
    public long getRetryDelayMsFor(LoadErrorInfo info) {
        if (!isConnectionError(info.exception)
                && info.errorCount > super.getMinimumLoadableRetryCount(info.mediaLoadData.dataType)) {
            return C.TIME_UNSET;
        }
        return super.getRetryDelayMsFor(info);
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        // The loader would otherwise fail playback with an error it is still retrying
        return Integer.MAX_VALUE;
    }

    private static boolean isConnectionError(IOException exception) {
        if (!(exception instanceof DataSourceException)) return false;
        int reason = ((DataSourceException) exception).reason;
        return reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
    }
}
