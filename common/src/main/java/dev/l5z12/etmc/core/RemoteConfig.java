package dev.l5z12.etmc.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches an etmc/EasyTier config from an HTTP(S) URL. Used by "connect via config URL": a server
 * admin hosts a small config file (an EasyTier TOML, optionally with an {@code [etmc] server} line,
 * or an {@code etmc://} link) and players paste the link.
 */
public final class RemoteConfig {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration BODY_TIMEOUT = Duration.ofSeconds(20);

    /** Runs before each request, so a redirect can't reach what the first URL couldn't. */
    @FunctionalInterface
    interface HopCheck {
        void check(URI uri) throws IOException;
    }

    private RemoteConfig() {}

    /** Downloads the config body. Blocking; call off the render thread. */
    public static String fetch(String url) throws IOException, InterruptedException {
        return fetch(url, uri -> { }, BODY_TIMEOUT);
    }

    /**
     * Downloads a config for an {@code etmc://v1/config/} link, refusing private and local addresses.
     * A player only pastes or clicks such a link, so it shouldn't be able to reach their own network.
     * Only https is allowed, because the address check resolves the name apart from the request, so
     * the name could change to a private address in between (DNS rebinding). A private service can't
     * present a certificate for that name, so the handshake fails before any request is sent.
     * Blocking; call off the render thread.
     */
    public static String fetchPublic(String url) throws IOException, InterruptedException {
        return fetch(url, RemoteConfig::requirePublic, BODY_TIMEOUT);
    }

    static String fetch(String url, HopCheck check, Duration bodyTimeout) throws IOException, InterruptedException {
        URI uri = parse(url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check.check(uri);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "etmc")
                    .header("Accept", "text/plain, application/toml, */*")
                    .GET()
                    .build();

            // Stream the body so an oversized (or endless) response is cut off as it arrives, instead of
            // being buffered whole and only then rejected.
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            try (InputStream in = resp.body()) {
                Optional<String> location = resp.headers().firstValue("Location");
                if (isRedirect(code) && location.isPresent()) {
                    uri = redirectTarget(uri, location.get());
                    continue;
                }
                if (code / 100 != 2) {
                    throw new IOException(Msg.tr("etmc.url.http_error", "server returned HTTP %s", code));
                }
                String body = readBody(in, bodyTimeout);
                if (body.isBlank()) {
                    throw new IOException(Msg.tr("etmc.url.empty_config", "empty config"));
                }
                return body;
            }
        }
        throw new IOException(Msg.tr("etmc.url.redirects", "too many redirects"));
    }

    /** Just the host of {@code url}, for logs: the rest of a URL can carry a token. */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "?" : host;
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(Msg.tr("etmc.url.empty", "Empty URL"));
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Msg.tr("etmc.url.invalid", "Invalid URL"));
        }
        if (!isHttp(uri)) {
            throw new IllegalArgumentException(Msg.tr("etmc.url.not_http", "URL must be http(s)://"));
        }
        return uri;
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Where a redirect leads, refusing anything but http(s), and any step down from https to http. */
    static URI redirectTarget(URI from, String location) throws IOException {
        URI next;
        try {
            next = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException(Msg.tr("etmc.url.bad_redirect", "the server sent a redirect etmc won't follow"));
        }
        boolean downgrade = "https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme());
        if (!isHttp(next) || downgrade) {
            throw new IOException(Msg.tr("etmc.url.bad_redirect", "the server sent a redirect etmc won't follow"));
        }
        return next;
    }

    static void requirePublic(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException(Msg.tr("etmc.url.https_only",
                    "this link must use https://. Use Connect via config URL for http://"));
        }
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException(Msg.tr("etmc.url.invalid", "Invalid URL"));
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isPrivate(address)) {
                throw new IOException(Msg.tr("etmc.url.private",
                        "this link can't load from a private address (%s). Use Connect via config URL instead", host));
            }
        }
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            return first == 0 || (first == 100 && (b[1] & 0xC0) == 64); // "this network", and carrier NAT
        }
        return (b[0] & 0xFE) == 0xFC; // IPv6 unique local
    }

    /**
     * Reads the body within {@code timeout}. The request's own timeout stops at the headers, so a
     * server that then goes quiet would otherwise hold etmc's only worker thread for good.
     */
    private static String readBody(InputStream in, Duration timeout) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> {
            expired.set(true);
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing only has to wake the read below.
            }
        }, CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS));
        try {
            String body = readCapped(in);
            if (expired.get()) throw timedOut();
            return body;
        } catch (IOException e) {
            if (expired.get()) throw timedOut();
            throw e;
        } finally {
            deadline.cancel(false);
        }
    }

    private static IOException timedOut() {
        return new IOException(Msg.tr("etmc.url.timeout", "the server took too long to send the config"));
    }

    /** Reads at most {@link #MAX_BYTES} of UTF-8 text, failing if the source has more to give. */
    private static String readCapped(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_BYTES + 1];
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        if (n > MAX_BYTES) {
            throw new IOException(Msg.tr("etmc.url.too_large", "config too large (>%s KB)", MAX_BYTES / 1024));
        }
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }
}
