package centuryroad.history;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * A stand-in for Wikipedia on a real socket, for the tests that must exercise the actual
 * HTTP stack - gzip in particular, which no mock of the RestClient can prove. It answers
 * only the paths a test registered, gzips when the client says it accepts it (as Wikipedia
 * does), and remembers every request so a test can assert on what was sent.
 */
public final class FakeWikipedia implements AutoCloseable {

    public record Received(String path, String userAgent, String acceptEncoding, String accept) {
    }

    public record Reply(int status, String contentType, String body, Map<String, String> headers) {

        public static Reply json(String body) {
            return new Reply(200, "application/json; charset=utf-8", body, Map.of());
        }

        public static Reply status(int status) {
            return new Reply(status, "text/html", "<html>" + status + "</html>", Map.of());
        }

        public static Reply status(int status, Map<String, String> headers) {
            return new Reply(status, "text/html", "<html>" + status + "</html>", headers);
        }
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    private FakeWikipedia(HttpServer server) {
        this.server = server;
    }

    public static FakeWikipedia start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeWikipedia fake = new FakeWikipedia(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake Wikipedia", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reply(String path, Reply reply) {
        replies.put(path, reply);
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public long requestsTo(String path) {
        return received.stream().filter(r -> r.path().equals(path)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(path, exchange.getRequestHeaders().getFirst("User-Agent"),
                exchange.getRequestHeaders().getFirst("Accept-Encoding"),
                exchange.getRequestHeaders().getFirst("Accept")));

        Reply reply = replies.getOrDefault(path, Reply.status(404));
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        boolean gzip = acceptEncoding != null && acceptEncoding.contains("gzip") && reply.status() == 200;
        if (gzip) {
            body = gzip(body);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(plain);
        }
        return buffer.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
