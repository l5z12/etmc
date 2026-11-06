package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A config link can come from anyone, so a fetch has to end, and has to stay off the player's network. */
@Timeout(30)
class RemoteConfigTest {

    private static final Duration LONG = Duration.ofSeconds(10);

    private ServerSocket server;
    private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) server.close();
        for (Socket s : open) s.close();
    }

    /** Gives each connection the next canned response, and leaves it open. */
    private String serve(String... responses) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try {
                for (String response : responses) {
                    Socket s = server.accept();
                    open.add(s);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip the request */ }
                    s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // The server closes under this loop when the test ends.
            }
        });
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private static String ok(String body) {
        return "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    private static String redirect(String to) {
        return "HTTP/1.1 302 Found\r\nConnection: close\r\nContent-Length: 0\r\nLocation: " + to + "\r\n\r\n";
    }

    @Test
    void readsTheBody() throws Exception {
        assertEquals("hello", RemoteConfig.fetch(serve(ok("hello")) + "/etmc.toml"));
    }

    @Test
    void checksEveryHopOfARedirect() throws Exception {
        List<String> hops = new ArrayList<>();
        String base = serve(redirect("/next"), ok("hello"));

        String body = RemoteConfig.fetch(base + "/first", uri -> hops.add(uri.getPath()), LONG);

        assertEquals("hello", body);
        assertEquals(List.of("/first", "/next"), hops);
    }

    @Test
    void stopsAtARefusedRedirect() throws Exception {
        String base = serve(redirect("/private"), ok("secret"));

        IOException e = assertThrows(IOException.class, () -> RemoteConfig.fetch(base + "/first", uri -> {
            if (uri.getPath().equals("/private")) throw new IOException("refused");
        }, LONG));
        assertEquals("refused", e.getMessage());
    }

    @Test
    void givesUpOnEndlessRedirects() throws Exception {
        String base = serve(redirect("/1"), redirect("/2"), redirect("/3"), redirect("/4"), redirect("/5"), redirect("/6"));

        IOException e = assertThrows(IOException.class, () -> RemoteConfig.fetch(base + "/0"));
        assertTrue(e.getMessage().contains("redirects"), e.getMessage());
    }

    @Test
    void aServerThatStallsMidBodyTimesOut() throws Exception {
        String base = serve("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial");

        IOException e = assertThrows(IOException.class,
                () -> RemoteConfig.fetch(base + "/slow", uri -> { }, Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
    }

    @Test
    void redirectsCantLeaveHttpOrDropHttps() throws Exception {
        URI secure = URI.create("https://example.com/a/b");
        assertEquals(URI.create("https://example.com/a/c"), RemoteConfig.redirectTarget(secure, "c"));
        assertEquals(URI.create("http://b.example/"),
                RemoteConfig.redirectTarget(URI.create("http://a.example/"), "http://b.example/"));
        assertThrows(IOException.class, () -> RemoteConfig.redirectTarget(secure, "http://example.com/"));
        assertThrows(IOException.class, () -> RemoteConfig.redirectTarget(secure, "file:///etc/passwd"));
    }

    @Test
    void configLinksNeedHttps() {
        for (String url : List.of("http://example.com/etmc.toml", "HTTP://127.0.0.1:1/x")) {
            IOException e = assertThrows(IOException.class, () -> RemoteConfig.fetchPublic(url), url);
            assertTrue(e.getMessage().contains("must use https://"), e.getMessage());
        }
    }

    /** The address check has passed, and the name now points at a local service that doesn't speak TLS. */
    @Test
    void aReboundNameNeverGetsARequest() throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        CompletableFuture<byte[]> firstBytes = CompletableFuture.supplyAsync(() -> {
            try (Socket s = server.accept()) {
                return s.getInputStream().readNBytes(3);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThrows(IOException.class,
                () -> RemoteConfig.fetch("https://127.0.0.1:" + server.getLocalPort() + "/x", uri -> { }, LONG));

        assertEquals(0x16, firstBytes.get(10, TimeUnit.SECONDS)[0], "only a TLS handshake record reaches the service");
    }

    @Test
    void configLinksRefuseLocalAddresses() {
        for (String url : List.of("https://127.0.0.1:1/x", "https://[::1]:1/x", "https://localhost:1/x")) {
            IOException e = assertThrows(IOException.class, () -> RemoteConfig.fetchPublic(url), url);
            assertTrue(e.getMessage().contains("private address"), e.getMessage());
        }
    }

    @Test
    void privateAddressesAreRecognised() throws UnknownHostException {
        for (String ip : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
                "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fd00::1", "fc00::1", "::ffff:192.168.1.1")) {
            assertTrue(RemoteConfig.isPrivate(InetAddress.getByName(ip)), ip);
        }
        for (String ip : List.of("1.1.1.1", "8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111")) {
            assertFalse(RemoteConfig.isPrivate(InetAddress.getByName(ip)), ip);
        }
    }

    @Test
    void hostOfDropsTheRestOfTheUrl() {
        assertEquals("example.com", RemoteConfig.hostOf(" https://user:pass@example.com/etmc.toml?token=abc "));
        assertEquals("?", RemoteConfig.hostOf("not a url"));
    }
}
