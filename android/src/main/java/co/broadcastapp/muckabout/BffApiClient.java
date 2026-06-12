package co.broadcastapp.muckabout;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Native HTTP client for fetching media browse data from the WNYC BFF API.
 * Used by RemoteStreamerService to populate the Android Auto media browse tree.
 */
public class BffApiClient {
    private static final String TAG = "BffApiClient";
    private static final int TIMEOUT_MS = 10000;

    private String baseUrl;

    public BffApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // --- Data classes ---

    public static class LiveStream {
        public String slug;
        public String stationName;
        public String currentShowTitle;
        public String hlsUrl;
        public String imageUrl;

        public LiveStream(String slug, String stationName, String currentShowTitle, String hlsUrl, String imageUrl) {
            this.slug = slug;
            this.stationName = stationName;
            this.currentShowTitle = currentShowTitle;
            this.hlsUrl = hlsUrl;
            this.imageUrl = imageUrl;
        }
    }

    public static class NewsItem {
        public String id;
        public String title;
        public String showTitle;
        public String audioUrl;
        public String imageUrl;
        public int durationSeconds;

        public NewsItem(String id, String title, String showTitle, String audioUrl, String imageUrl, int durationSeconds) {
            this.id = id;
            this.title = title;
            this.showTitle = showTitle;
            this.audioUrl = audioUrl;
            this.imageUrl = imageUrl;
            this.durationSeconds = durationSeconds;
        }
    }

    public static class StoryItem {
        public String id;
        public String title;
        public String showTitle;
        public String audioUrl;
        public String imageUrl;
        public int durationSeconds;

        public StoryItem(String id, String title, String showTitle, String audioUrl, String imageUrl, int durationSeconds) {
            this.id = id;
            this.title = title;
            this.showTitle = showTitle;
            this.audioUrl = audioUrl;
            this.imageUrl = imageUrl;
            this.durationSeconds = durationSeconds;
        }
    }

    public static class Show {
        public String slug;
        public String title;
        public String imageUrl;

        public Show(String slug, String title, String imageUrl) {
            this.slug = slug;
            this.title = title;
            this.imageUrl = imageUrl;
        }
    }

    public static class Episode {
        public String id;
        public String title;
        public String showTitle;
        public String audioUrl;
        public String imageUrl;
        public int durationSeconds;

        public Episode(String id, String title, String showTitle, String audioUrl, String imageUrl, int durationSeconds) {
            this.id = id;
            this.title = title;
            this.showTitle = showTitle;
            this.audioUrl = audioUrl;
            this.imageUrl = imageUrl;
            this.durationSeconds = durationSeconds;
        }
    }

    // --- API methods ---

    /**
     * Fetch live streams from /api/streams
     */
    public List<LiveStream> fetchLiveStreams() {
        List<LiveStream> streams = new ArrayList<>();
        try {
            String json = httpGet(baseUrl + "/api/streams");
            if (json == null) return streams;

            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String slug = obj.optString("slug", "");
                String stationName = obj.optString("station", "");
                String currentShow = obj.optString("title", obj.optString("showTitle", ""));

                // Image is a direct URL string
                String imageUrl = obj.optString("image", "");
                if (imageUrl.isEmpty()) {
                    // Fallback to stationImage.url
                    JSONObject stationImage = obj.optJSONObject("stationImage");
                    if (stationImage != null) {
                        String template = stationImage.optString("template", "");
                        if (!template.isEmpty()) {
                            imageUrl = template.replace("%s/%s/%s/%s", "512/512/c/80");
                        } else {
                            imageUrl = stationImage.optString("url", "");
                        }
                    }
                }

                // HLS URL
                String hlsUrl = obj.optString("hls", "");
                if (hlsUrl.isEmpty()) {
                    hlsUrl = obj.optString("audio", obj.optString("file", ""));
                }

                if (!hlsUrl.isEmpty()) {
                    streams.add(new LiveStream(slug, stationName, currentShow, hlsUrl, imageUrl));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing live streams", e);
        }
        return streams;
    }

    /**
     * Fetch latest news from /api/homepagelatestnewsupdates
     */
    public List<NewsItem> fetchLatestNews() {
        List<NewsItem> news = new ArrayList<>();
        try {
            String json = httpGet(baseUrl + "/api/homepagelatestnewsupdates");
            if (json == null) return news;

            JSONObject obj = new JSONObject(json);

            // Local newscast (NYC Headlines)
            JSONObject local = obj.optJSONObject("local_newscast");
            if (local == null) local = obj.optJSONObject("localNewscast");
            if (local != null) {
                news.add(parseNewsItem(local, "local_newscast"));
            }

            // National newscast (NPR News Now)
            JSONObject national = obj.optJSONObject("national_newscast");
            if (national == null) national = obj.optJSONObject("nationalNewscast");
            if (national != null) {
                news.add(parseNewsItem(national, "national_newscast"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing latest news", e);
        }
        return news;
    }

    /**
     * Fetch top stories from /api/curated_lists/4 (demo) or /api/curated_lists/87 (prod)
     * Returns up to 4 items that have audio
     */
    public List<StoryItem> fetchTopStories() {
        List<StoryItem> stories = new ArrayList<>();
        try {
            // Using list ID 4 for demo, 87 for prod
            String json = httpGet(baseUrl + "/api/curated_lists/87");
            if (json == null) return stories;

            JSONObject obj = new JSONObject(json);
            JSONArray listItems = obj.optJSONArray("listItems");
            if (listItems == null) listItems = obj.optJSONArray("list_items");
            if (listItems == null) return stories;

            for (int i = 0; i < listItems.length() && stories.size() < 4; i++) {
                JSONObject item = listItems.getJSONObject(i);

                // Only include items that explicitly have audio
                if (!item.optBoolean("hasAudio", false)) continue;
                String audioUrl = item.optString("audio", "");
                if (audioUrl.isEmpty()) continue;

                String id = item.optString("id", "story_" + i);
                String title = item.optString("title", "");
                String showTitle = item.optString("showTitle", item.optString("show_title", ""));
                int duration = item.optInt("estimatedDuration", item.optInt("estimated_duration", 0));

                String imageUrl = resolveImageUrl(item, true, "image");

                stories.add(new StoryItem(id, title, showTitle, audioUrl, imageUrl, duration));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing top stories", e);
        }
        return stories;
    }

    /**
     * Fetch all shows from /api/v2/discover/shows
     */
    public List<Show> fetchAllShows() {
        List<Show> shows = new ArrayList<>();
        try {
            String json = httpGet(baseUrl + "/api/v3/shows");
            if (json == null) return shows;

            JSONObject obj = new JSONObject(json);
            // just interested in the featured shows for now, which is what Android Auto will show
            JSONArray allShows = obj.optJSONArray("all");
            if (allShows == null) return shows;

            for (int i = 0; i < allShows.length(); i++) {
                JSONObject showObj = allShows.getJSONObject(i);
                String slug = showObj.optString("slug", showObj.optString("id", ""));
                // Primary shape is top-level fields (title, image, showArt), with legacy attrs fallback.
                String title = showObj.optString("title", "");
                String imageUrl = resolveImageUrl(showObj, false, "image", "showArt", "logoImage", "logo_image");

                if (title.isEmpty() || imageUrl.isEmpty()) {
                    JSONObject attrs = showObj.optJSONObject("attributes");
                    if (attrs != null) {
                        if (title.isEmpty()) {
                            title = attrs.optString("title", "");
                        }
                        if (imageUrl.isEmpty()) {
                            imageUrl = resolveImageUrl(attrs, false, "image", "image-main", "imageMain");
                        }
                    }
                }

                if (!title.isEmpty()) {
                    shows.add(new Show(slug, title, imageUrl));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing shows", e);
        }
        return shows;
    }

    /**
     * Fetch episodes for a show. First resolves the podcastId from the show page,
     * then fetches episodes using that podcastId.
     */
    public List<Episode> fetchEpisodes(String showSlug) {
        List<Episode> episodes = new ArrayList<>();
        try {
            // Step 1: Fetch show page to get podcastId from linkedDataSource
            String showJson = httpGet(baseUrl + "/api/pages/wagtail/" + showSlug + "?showOnly=true");
            if (showJson == null) return episodes;

            JSONObject showObj = new JSONObject(showJson);
            JSONArray linkedDataSource = showObj.optJSONArray("linkedDataSource");
            if (linkedDataSource == null || linkedDataSource.length() == 0) return episodes;

            JSONObject firstSource = linkedDataSource.getJSONObject(0);
            JSONObject value = firstSource.optJSONObject("value");
            if (value == null) return episodes;

            String podcastId = value.optString("id", "");
            if (podcastId.isEmpty()) return episodes;

            // Step 2: Fetch episodes using the podcastId
            String json = httpGet(baseUrl + "/api/v3/show/" + podcastId + "/episodes?limit=20");
            if (json == null) return episodes;

            JSONObject obj = new JSONObject(json);
            JSONArray data = obj.optJSONArray("data");
            if (data == null) {
                // Might be a direct array
                try {
                    data = new JSONArray(json);
                } catch (JSONException ignored) {
                    return episodes;
                }
            }

            for (int i = 0; i < data.length(); i++) {
                JSONObject ep = data.getJSONObject(i);
                JSONObject attrs = ep.optJSONObject("attributes");
                if (attrs == null) attrs = ep;

                String id = ep.optString("id", "ep_" + i);
                String title = attrs.optString("title", ep.optString("title", ""));
                String showTitle = attrs.optString("show_title", attrs.optString("showTitle", ep.optString("showTitle", "")));
                String audioUrl = attrs.optString("audio", attrs.optString("file", ep.optString("audio", ep.optString("file", ""))));
                String imageUrl = resolveImageUrl(attrs, false, "image", "showArt", "logoImage", "logo_image");
                if (imageUrl.isEmpty()) {
                    imageUrl = resolveImageUrl(ep, false, "image", "showArt", "logoImage", "logo_image");
                }
                int duration = attrs.optInt("duration", attrs.optInt("estimated_duration", 0));

                if (!audioUrl.isEmpty()) {
                    episodes.add(new Episode(id, title, showTitle, audioUrl, imageUrl, duration));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing episodes for " + showSlug, e);
        }
        return episodes;
    }

    // --- Helpers ---

    private NewsItem parseNewsItem(JSONObject obj, String fallbackId) throws JSONException {
        String id = obj.optString("id", fallbackId);
        String title = obj.optString("card_title", obj.optString("cardTitle", obj.optString("title", "")));
        String showTitle = obj.optString("show_title", obj.optString("showTitle", ""));
        String audioUrl = obj.optString("file", obj.optString("audio", ""));
        String imageUrl = "";
        JSONObject headers = obj.optJSONObject("headers");
        if (headers != null) {
            JSONObject brand = headers.optJSONObject("brand");
            if (brand != null) {
                JSONObject logoImage = brand.optJSONObject("logoImage");
                if (logoImage != null) {
                    imageUrl = normalizeImageUrl(logoImage.optString("url", ""));
                }
            }
        }
        int duration = obj.optInt("duration", obj.optInt("estimated_duration", 0));

        return new NewsItem(id, title, showTitle, audioUrl, imageUrl, duration);
    }

    private String resolveImageUrl(JSONObject container, boolean includeBrandFallback, String... imageKeys) {
        return resolveImageUrl(container, includeBrandFallback, false, imageKeys);
    }

    private String resolveImageUrl(JSONObject container, boolean includeBrandFallback, boolean forceFallback, String... imageKeys) {
        if (container == null) return "";

        if (!forceFallback) {
            for (String imageKey : imageKeys) {
                String imageUrl = resolveImageCandidate(container.opt(imageKey));
                if (!imageUrl.isEmpty()) {
                    return imageUrl;
                }
            }
        }

        if (includeBrandFallback) {
            return resolveBrandLogoUrl(container);
        }

        return "";
    }

    private String resolveImageCandidate(Object imageObj) {
        if (imageObj instanceof JSONObject) {
            JSONObject image = (JSONObject) imageObj;
            String imageUrl = image.optString("file", image.optString("url", ""));
            if (imageUrl.isEmpty()) {
                String template = image.optString("template", "");
                if (!template.isEmpty()) {
                    imageUrl = template.replace("%s/%s/%s/%s", "200/200/c/80");
                }
            }
            return normalizeImageUrl(imageUrl);
        }

        if (imageObj instanceof String) {
            return normalizeImageUrl((String) imageObj);
        }

        return "";
    }

    private String resolveBrandLogoUrl(JSONObject container) {
        JSONObject headers = container.optJSONObject("headers");
        if (headers == null) return "";

        JSONObject brand = headers.optJSONObject("brand");
        if (brand == null) return "";

        JSONObject logoImage = brand.optJSONObject("logoImage");
        if (logoImage == null) logoImage = brand.optJSONObject("logo_image");
        if (logoImage == null) return "";

        return normalizeImageUrl(logoImage.optString("url", ""));
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return "";

        if (imageUrl.contains("npr.brightspotcdn.com")) {
            return imageUrl.replace("{width}", "512")
                    .replace("{quality}", "80")
                    .replace("{format}", "jpg");
        }

        return imageUrl;
    }

    private String httpGet(String urlStr) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setInstanceFollowRedirects(true);

            int code = connection.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " from " + urlStr);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "HTTP request failed: " + urlStr, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
