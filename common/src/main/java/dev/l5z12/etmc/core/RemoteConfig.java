package dev.l5z12.etmc.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches an etmc/EasyTier config from an HTTP(S) URL. Used by "connect via config URL": a server
 * admin hosts a small config file (an EasyTier TOML, optionally with an {@code [etmc] server} line,
 * or an {@code ETMC1:} join code) and players paste the link.
 */
public final class RemoteConfig {

    private static final int MAX_BYTES = 512 * 1024;

    private RemoteConfig() {}

    /** Downloads the config body. Blocking; call off the render thread. */
    public static String fetch(String url) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Empty URL");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("URL must be http(s)://");
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "etmc")
                .header("Accept", "text/plain, application/toml, */*")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        if (code / 100 != 2) {
            throw new IOException("server returned HTTP " + code);
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new IOException("empty config");
        }
        if (body.length() > MAX_BYTES) {
            throw new IOException("config too large (>" + (MAX_BYTES / 1024) + " KB)");
        }
        return body;
    }
}
