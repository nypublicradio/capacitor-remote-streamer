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
                String stationName = obj.optString("station_name", obj.optString("stationName", ""));
                String currentShow = "";
                String imageUrl = "";

                // Try to get current show info
                JSONObject currentEpisode = obj.optJSONObject("current_episode");
                if (currentEpisode == null) currentEpisode = obj.optJSONObject("currentEpisode");
                if (currentEpisode != null) {
                    currentShow = currentEpisode.optString("title", "");
                    JSONObject image = currentEpisode.optJSONObject("image");
                    if (image != null) {
                        imageUrl = image.optString("url", "");
                    }
                }

                // Get HLS URL
                String hlsUrl = obj.optString("hls_url", obj.optString("hlsUrl", ""));
                if (hlsUrl.isEmpty()) {
                    hlsUrl = obj.optString("audio_url", obj.optString("audioUrl", ""));
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
     * Fetch top stories from /api/homepagecuration (up to 4 with audio)
     */
    public List<StoryItem> fetchTopStories() {
        List<StoryItem> stories = new ArrayList<>();
        try {
            String json = httpGet(baseUrl + "/api/homepagecuration");
            if (json == null) return stories;

            JSONObject obj = new JSONObject(json);
            JSONObject template = obj.optJSONObject("new_home_template");
            if (template == null) template = obj.optJSONObject("newHomeTemplate");
            if (template == null) return stories;

            JSONArray curated = template.optJSONArray("curated_content");
            if (curated == null) curated = template.optJSONArray("curatedContent");
            if (curated == null) return stories;

            for (int i = 0; i < curated.length() && stories.size() < 4; i++) {
                JSONObject item = curated.getJSONObject(i);
                String audioUrl = item.optString("audio", item.optString("file", ""));
                if (audioUrl.isEmpty()) continue;

                String id = item.optString("id", "story_" + i);
                String title = item.optString("title", "");
                String showTitle = item.optString("show_title", item.optString("showTitle", ""));
                String imageUrl = "";
                JSONObject image = item.optJSONObject("image");
                if (image != null) {
                    imageUrl = image.optString("url", "");
                }
                int duration = item.optInt("duration", item.optInt("estimated_duration", 0));

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
            String json = httpGet(baseUrl + "/api/v2/discover/shows");
            if (json == null) return shows;

            JSONObject obj = new JSONObject(json);
            JSONArray data = obj.optJSONArray("data");
            if (data == null) return shows;

            for (int i = 0; i < data.length(); i++) {
                JSONObject showObj = data.getJSONObject(i);
                String slug = showObj.optString("slug", showObj.optString("id", ""));
                JSONObject attrs = showObj.optJSONObject("attributes");
                String title = "";
                String imageUrl = "";
                if (attrs != null) {
                    title = attrs.optString("title", "");
                    JSONObject image = attrs.optJSONObject("image-main");
                    if (image == null) image = attrs.optJSONObject("imageMain");
                    if (image != null) {
                        // Use the template URL with size params or the direct URL
                        imageUrl = image.optString("url", "");
                        if (imageUrl.isEmpty()) {
                            String template = image.optString("template", "");
                            if (!template.isEmpty()) {
                                imageUrl = template.replace("%s/%s/%s/%s", "200/200/c/80");
                            }
                        }
                    }
                } else {
                    title = showObj.optString("title", "");
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
     * Fetch episodes for a show from /api/v3/show/{slug}/episodes
     */
    public List<Episode> fetchEpisodes(String showSlug) {
        List<Episode> episodes = new ArrayList<>();
        try {
            String json = httpGet(baseUrl + "/api/v3/show/" + showSlug + "/episodes?limit=20");
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
                String title = attrs.optString("title", "");
                String showTitle = attrs.optString("show_title", attrs.optString("showTitle", ""));
                String audioUrl = attrs.optString("audio", attrs.optString("file", ""));
                String imageUrl = "";
                JSONObject image = attrs.optJSONObject("image");
                if (image != null) {
                    imageUrl = image.optString("url", "");
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
        JSONObject image = obj.optJSONObject("image");
        if (image != null) {
            imageUrl = image.optString("url", "");
        }
        JSONObject headers = obj.optJSONObject("headers");
        if (headers != null && imageUrl.isEmpty()) {
            JSONObject brand = headers.optJSONObject("brand");
            if (brand != null) {
                JSONObject logoImage = brand.optJSONObject("logo_image");
                if (logoImage == null) logoImage = brand.optJSONObject("logoImage");
                if (logoImage != null) {
                    imageUrl = logoImage.optString("url", "");
                }
            }
        }
        int duration = obj.optInt("duration", obj.optInt("estimated_duration", 0));

        return new NewsItem(id, title, showTitle, audioUrl, imageUrl, duration);
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
