package co.broadcastapp.muckabout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Collections;
import org.junit.Test;

public class ConnectionRetryPolicyTest {

    private final ConnectionRetryPolicy policy = new ConnectionRetryPolicy();

    @Test
    public void retriesConnectionErrorsWithoutFailingPlayback() {
        for (int reason : new int[] {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        }) {
            IOException offline = new HttpDataSourceException(new UnknownHostException(), null, reason,
                HttpDataSourceException.TYPE_OPEN);
            LoadErrorInfo fiftiethFailure = failure(offline, 50);
            assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(fiftiethFailure));
            assertTrue("the loader surfaces an error once its count passes this",
                50 <= policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
        }
    }

    @Test
    public void otherErrorsFailAfterTheDefaultNumberOfAttempts() {
        IOException notFound = new InvalidResponseCodeException(404, "Not Found", null, Collections.emptyMap(),
            null, new byte[0]);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertNotEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(failure(notFound, attempt)));
        }
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(failure(notFound, 4)));
    }

    // No DataSpec or LoadEventInfo: they need an android.net.Uri, which is a null stub in JVM tests
    private static LoadErrorInfo failure(IOException exception, int errorCount) {
        return new LoadErrorInfo(null, new MediaLoadData(C.DATA_TYPE_MEDIA), exception, errorCount);
    }
}
