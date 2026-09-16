package com.killer560.hub.discordrpc;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Dependency-free Discord IPC client - no JNA, no discord-rpc native, no extra library. Talks the same
 * local socket protocol the official Discord Game SDK uses:
 * <ul>
 *   <li>Transport: Windows named pipe {@code \\.\pipe\discord-ipc-N} (N = 0..9, first one that opens wins);
 *       everywhere else a unix domain socket {@code discord-ipc-N} under {@code $XDG_RUNTIME_DIR},
 *       {@code $TMPDIR}, {@code $TMP}, {@code $TEMP} or {@code /tmp} (also inside the Flatpak/Snap
 *       subfolders Discord uses there).</li>
 *   <li>Framing: little-endian int32 opcode, little-endian int32 payload length, then UTF-8 JSON.</li>
 *   <li>Opcode 0 HANDSHAKE with {@code {"v":1,"client_id":"..."}}, then opcode 1 frames carrying
 *       {@code {"cmd":"SET_ACTIVITY","nonce":"...","args":{"pid":N,"activity":{...}}}}.</li>
 * </ul>
 * Every method here BLOCKS - it is only ever called from {@link DiscordRpcFeature}'s single daemon
 * worker thread, never from the client/render thread. Each {@link #setActivity} also reads the reply
 * frame back, both to notice a dropped connection and so Discord's pipe buffer can't slowly fill up over
 * a long session.
 */
final class DiscordIpcClient implements Closeable {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-discordrpc");

    static final int OP_HANDSHAKE = 0;
    static final int OP_FRAME = 1;
    static final int OP_CLOSE = 2;

    /** Sanity cap on an incoming frame so a garbage length can't make us allocate wildly. */
    private static final int MAX_FRAME_BYTES = 256 * 1024;

    private final Transport transport;

    private DiscordIpcClient(Transport transport) {
        this.transport = transport;
    }

    // ------------------------------------------------------------------------------------- transports

    private interface Transport extends Closeable {
        void write(byte[] data) throws IOException;

        void readFully(byte[] buf, int len) throws IOException;
    }

    /** Windows: a named pipe opened as a plain file handle. */
    private static final class PipeTransport implements Transport {
        private final RandomAccessFile file;

        PipeTransport(String path) throws IOException {
            this.file = new RandomAccessFile(path, "rw");
        }

        @Override
        public void write(byte[] data) throws IOException {
            file.write(data);
        }

        @Override
        public void readFully(byte[] buf, int len) throws IOException {
            file.readFully(buf, 0, len);
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    /** Linux/macOS: a unix domain socket (JDK 16+ {@link UnixDomainSocketAddress}). */
    private static final class SocketTransport implements Transport {
        private final SocketChannel channel;

        SocketTransport(Path path) throws IOException {
            this.channel = SocketChannel.open(UnixDomainSocketAddress.of(path));
        }

        @Override
        public void write(byte[] data) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                if (channel.write(buf) < 0) {
                    throw new IOException("Discord socket closed while writing");
                }
            }
        }

        @Override
        public void readFully(byte[] buf, int len) throws IOException {
            ByteBuffer dst = ByteBuffer.wrap(buf, 0, len);
            while (dst.hasRemaining()) {
                if (channel.read(dst) < 0) {
                    throw new IOException("Discord socket closed while reading");
                }
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Candidate directories a unix {@code discord-ipc-N} socket can live in, in priority order. */
    private static List<Path> unixSocketDirs() {
        List<Path> bases = new ArrayList<>();
        for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                bases.add(Path.of(value));
            }
        }
        bases.add(Path.of("/tmp"));

        List<Path> dirs = new ArrayList<>();
        for (Path base : bases) {
            dirs.add(base);
            // Flatpak / Snap installs put their own socket one level down.
            dirs.add(base.resolve("app").resolve("com.discordapp.Discord"));
            dirs.add(base.resolve("snap.discord"));
        }
        return dirs;
    }

    private static Transport open(int index) throws IOException {
        if (isWindows()) {
            return new PipeTransport("\\\\.\\pipe\\discord-ipc-" + index);
        }
        IOException last = null;
        for (Path dir : unixSocketDirs()) {
            Path socket = dir.resolve("discord-ipc-" + index);
            try {
                if (!Files.exists(socket)) {
                    continue;
                }
                return new SocketTransport(socket);
            } catch (IOException e) {
                last = e;
            } catch (RuntimeException e) {
                last = new IOException(e);
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ connecting

    /**
     * Opens the first available Discord IPC socket (0..9) and completes the handshake.
     *
     * @throws IOException when Discord isn't running, the app id is rejected, or every socket failed.
     */
    static DiscordIpcClient connect(String clientId) throws IOException {
        if (clientId == null || clientId.isBlank()) {
            throw new IOException("no Discord application id configured");
        }
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            Transport transport = null;
            try {
                transport = open(i);
                if (transport == null) {
                    continue;
                }
                DiscordIpcClient client = new DiscordIpcClient(transport);
                client.handshake(clientId.trim());
                return client;
            } catch (IOException e) {
                last = e;
                closeQuietly(transport);
            } catch (RuntimeException e) {
                last = new IOException(e);
                closeQuietly(transport);
            }
        }
        throw last != null ? last : new IOException("no Discord IPC socket found (is Discord running?)");
    }

    private void handshake(String clientId) throws IOException {
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", clientId);
        send(OP_HANDSHAKE, hello.toString());

        Frame reply = readFrame();
        LOGGER.debug("[DiscordRPC] handshake reply op={} {}", reply.op(), reply.payload());
        if (reply.op() == OP_CLOSE) {
            // Discord sends a CLOSE frame with a reason for e.g. an application id that doesn't exist.
            throw new IOException("Discord refused the connection: " + reply.payload());
        }
    }

    // -------------------------------------------------------------------------------------- activity

    /**
     * Sends a SET_ACTIVITY frame and drains the reply.
     *
     * @param activity the activity object, or {@code null} to clear the presence.
     */
    void setActivity(JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        // Real pid: Discord clears the presence automatically if the game dies without a clean shutdown.
        // (Sending 0 was tried on 2026-09-16 to dodge Medal's Minecraft presence and made no difference.)
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity == null) {
            args.add("activity", JsonNull.INSTANCE);
        } else {
            args.add("activity", activity);
        }

        JsonObject frame = new JsonObject();
        frame.addProperty("cmd", "SET_ACTIVITY");
        frame.addProperty("nonce", UUID.randomUUID().toString());
        frame.add("args", args);

        send(OP_FRAME, frame.toString());
        Frame reply = readFrame();
        // Discord answers every SET_ACTIVITY; an "evt":"ERROR" reply is the only way to see WHY a presence
        // silently fails to appear (bad asset key, unknown application, malformed activity...). Logged at INFO
        // so a normal run shows exactly what was sent and what came back.
        // Only worth a line when Discord actually complains - this runs every 15s for the whole session.
        if (reply.payload() != null && reply.payload().contains("\"evt\":\"ERROR\"")) {
            LOGGER.warn("[DiscordRPC] Discord rejected the presence: {}", reply.payload());
        }
    }

    // ----------------------------------------------------------------------------------- raw framing

    record Frame(int op, String payload) {
    }

    private void send(int op, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(op);
        buf.putInt(payload.length);
        buf.put(payload);
        transport.write(buf.array());
    }

    private Frame readFrame() throws IOException {
        byte[] header = new byte[8];
        transport.readFully(header, 8);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int op = buf.getInt();
        int length = buf.getInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("bad Discord frame length " + length);
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            transport.readFully(payload, length);
        }
        return new Frame(op, new String(payload, StandardCharsets.UTF_8));
    }

    /** Best-effort CLOSE frame, then drops the socket. Discord clears the presence on its own. */
    @Override
    public void close() {
        try {
            send(OP_CLOSE, "{}");
        } catch (Exception ignored) {
        }
        closeQuietly(transport);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
