import com.killer560.hub.voicetotext.VoskLibrary;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Drives VoskLibrary - the real class from src/main, not a copy - through each first-use case, one case per JVM
 * (VoskLibrary caches what it loaded for the life of the JVM, exactly as it does in game). See run.sh.
 *
 *   java VoskCheck <case> <dir> [modelDir]
 *
 * A local HTTP server serves the Gradle cache's vosk-0.3.45.jar (-Dvoskcheck.jar) at /good, a copy with one byte
 * flipped at /corrupt and a 404 at /missing; a raw socket promises the whole jar and closes after half of it. Every case prints PASS or throws; run.sh requires one PASS line per case.
 */
public class VoskCheck {

    static final AtomicInteger requests = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path dir = Path.of(args[1]);
        byte[] jar = Files.readAllBytes(Path.of(System.getProperty("voskcheck.jar")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good", ex -> { requests.incrementAndGet(); send(ex, jar, jar.length); });
        server.createContext("/corrupt", ex -> {
            requests.incrementAndGet();
            byte[] bad = jar.clone();
            bad[bad.length / 2] ^= 0x01;
            send(ex, bad, bad.length);
        });
        server.createContext("/missing", ex -> { requests.incrementAndGet(); ex.sendResponseHeaders(404, -1); ex.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // A connection cut halfway: the full Content-Length is promised, half is sent, the socket is closed.
        // Raw socket, because HttpServer keeps the connection open and the client then waits out its read timeout.
        java.net.ServerSocket cut = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread cutter = new Thread(() -> {
            while (!cut.isClosed()) {
                try (java.net.Socket s = cut.accept()) {
                    requests.incrementAndGet();
                    s.getInputStream().read(new byte[8192]);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + jar.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.write(jar, 0, jar.length / 2);
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        }, "cutter");
        cutter.setDaemon(true);
        cutter.start();
        String cutBase = "http://127.0.0.1:" + cut.getLocalPort();
        try {
            run(scenario, dir, base, cutBase, args.length > 2 ? args[2] : null);
        } finally {
            server.stop(0);
            cut.close();
        }
    }

    static void run(String scenario, Path dir, String base, String cutBase, String modelDir) throws Exception {
        switch (scenario) {
            case "offline" -> {
                // The testkit's switch. VoskLibrary.DEFAULT_URL is the real Maven Central address.
                System.setProperty("killer560.net.offline", "true");
                expectFailure(dir, VoskLibrary.DEFAULT_URL, "check your connection");
            }
            case "corrupt" -> expectFailure(dir, base + "/corrupt", "integrity check");
            case "truncated" -> expectFailure(dir, cutBase + "/truncated", "check your connection");
            case "missing" -> expectFailure(dir, base + "/missing", "HTTP 404");
            case "download" -> {
                // A crashed earlier attempt's temporary file must be swept, not loaded.
                Files.createDirectories(dir);
                Path stale = dir.resolve(VoskLibrary.JAR_NAME + ".123.part");
                Files.write(stale, new byte[]{1, 2, 3});
                VoskLibrary lib = loadAndUse(dir, base + "/good", modelDir);
                check(requests.get() == 1, "expected exactly one download, saw " + requests.get());
                check(!Files.exists(stale), "the leftover .part file was not deleted");
                check(lib != null, "no library");
                pass("download: fetched once over HTTP, verified, loaded; stale .part removed");
            }
            case "cached" -> {
                // Second launch: the network is off and the URL is dead, so only the cached jar can satisfy it.
                System.setProperty("killer560.net.offline", "true");
                check(VoskLibrary.isCached(dir), "isCached() is false with the jar on disk");
                loadAndUse(dir, base + "/good", modelDir);
                check(requests.get() == 0, "cached launch made " + requests.get() + " request(s)");
                pass("cached: loaded from disk with the network off, zero requests");
            }
            case "tampered" -> {
                // The cached jar was damaged since it was verified; it must be replaced, not loaded.
                Path jarFile = dir.resolve(VoskLibrary.JAR_NAME);
                byte[] bytes = Files.readAllBytes(jarFile);
                bytes[1000] ^= 0x01;
                Files.write(jarFile, bytes);
                loadAndUse(dir, base + "/good", modelDir);
                check(requests.get() == 1, "expected one re-download, saw " + requests.get());
                pass("tampered: cached jar failed its hash, was deleted and fetched again");
            }
            case "central" -> {
                // The real address the mod uses, end to end.
                loadAndUse(dir, VoskLibrary.DOWNLOAD_URL, modelDir);
                pass("central: downloaded from " + VoskLibrary.DOWNLOAD_URL + " and the SHA-256 matched");
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    static VoskLibrary loadAndUse(Path dir, String url, String modelDir) throws Exception {
        VoskLibrary lib = VoskLibrary.load(dir, url, u -> System.out.println("  onDownload: " + u));
        Path jarFile = dir.resolve(VoskLibrary.JAR_NAME);
        check(Files.exists(jarFile), "no jar at " + jarFile);
        try (Stream<Path> s = Files.list(dir)) {
            check(s.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "a .part file was left behind");
        }
        ClassLoader child = lib.classLoader();
        Class<?> model = Class.forName("org.vosk.Model", false, child);
        check(model.getClassLoader() == child, "org.vosk.Model was not defined by the child loader");
        Class<?> jna = Class.forName("com.sun.jna.Native", false, child);
        check(jna.getClassLoader() != child, "JNA came from the child loader, not the parent");
        try {
            Class.forName("org.vosk.Model", false, VoskCheck.class.getClassLoader());
            throw new AssertionError("org.vosk is visible to the parent loader - the check proves nothing");
        } catch (ClassNotFoundException expected) {
        }
        // A real native call through JNA: fails with UnsatisfiedLinkError if the DLLs did not load.
        lib.setLogLevel("WARNINGS");
        System.out.println("  LibVosk.setLogLevel(WARNINGS) returned - libvosk is loaded; JNA from "
                + jna.getProtectionDomain().getCodeSource().getLocation());
        if (modelDir != null) {
            try (AutoCloseable m = lib.newModel(modelDir)) {
                byte[] silence = new byte[16000 * 2]; // one second of 16 kHz 16-bit silence
                String json = lib.recognize(m, 16000f, silence);
                System.out.println("  org.vosk.Model constructed; recognize(1s silence) = " + json.replaceAll("\\s+", " "));
                check(json.contains("\"text\""), "recognizer result has no text field: " + json);
            }
        }
        return lib;
    }

    static void expectFailure(Path dir, String url, String messagePart) throws Exception {
        try {
            VoskLibrary.load(dir, url, u -> { });
            throw new AssertionError("load() succeeded from " + url);
        } catch (IOException e) {
            check(e.getMessage().contains(messagePart), "message '" + e.getMessage() + "' lacks '" + messagePart + "'");
            check(!Files.exists(dir.resolve(VoskLibrary.JAR_NAME)), "a jar was left at the final path");
            try (Stream<Path> s = Files.list(dir)) {
                check(s.findAny().isEmpty(), "the folder is not empty after a failed download");
            }
            int reqs = requests.get();
            pass("refused (" + reqs + " request(s)): " + e.getMessage());
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, byte[] body, int len) throws IOException {
        ex.sendResponseHeaders(200, len);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body, 0, len);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void pass(String what) {
        System.out.println("PASS " + what);
    }
}
