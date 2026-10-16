package co.broadcastapp.muckabout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import co.broadcastapp.muckabout.library.PlayableItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class PlayerSettingsTest {

    @Test
    public void rendersUserAgentTemplate() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("appVersion", "3.2.1");
        tokens.put("osVersion", "15");
        tokens.put("deviceModel", "Pixel 9");
        assertEquals(
            "WNYC-App/3.2.1 (Android 15; Pixel 9)",
            PlayerSettings.renderUserAgent("WNYC-App/{appVersion} (Android {osVersion}; {deviceModel})", tokens)
        );
    }

    @Test
    public void treatsUnsetConfigValuesAsMissing() {
        assertNull(PlayerSettings.nonEmpty(null));
        assertNull(PlayerSettings.nonEmpty(""));
        assertNull(PlayerSettings.nonEmpty("  "));
        assertNull(PlayerSettings.nonEmpty("undefined"));
        assertNull(PlayerSettings.nonEmpty("null"));
        assertEquals("ic_stat_wnyc", PlayerSettings.nonEmpty(" ic_stat_wnyc "));
    }

    @Test
    public void playableItemReportsCanonicalUrl() {
        PlayableItem item = new PlayableItem("live:wnyc-fm939", "https://example.org/s.aac?listenerid=1",
            "https://example.org/s.aac", "WNYC 93.9 FM", "", "", null, true, 0);
        assertEquals("https://example.org/s.aac", item.getReportedUrl());
        assertEquals("https://example.org/e.mp3", PlayableItem.fromUrl("https://example.org/e.mp3", false).getReportedUrl());
    }

    @Test
    public void withMetadataKeepsIdentity() {
        PlayableItem item = PlayableItem.fromUrl("https://example.org/e.mp3", false)
            .withMetadata("Episode", "Show", "", "https://example.org/a.jpg", 1800);
        assertNull(item.mediaId);
        assertEquals("https://example.org/e.mp3", item.url);
        assertEquals("Episode", item.title);
        assertEquals(1800, item.durationSeconds, 0.001);
    }
}
