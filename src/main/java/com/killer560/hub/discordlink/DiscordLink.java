package com.killer560.hub.discordlink;

import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayCalls;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Discord Link (killer560, 2026-11-05: "for them to link their discord account so it lets me know their discord as well ...
 * may be useful for other things in the future too"): a verified link between this Minecraft account and the player's
 * Discord, held by the relay (killer560s-mod-relay src/discordlink.ts), never in a file here.
 * <p>
 * {@link #start} asks the relay for a one-time code (10 minutes); the player types {@code /link code:<code>} in killer560's
 * Discord. While a code is live the relay is asked every {@value #POLL_S} s whether it was used, so the card and the tour turn
 * to "Linked" by themselves. {@link #refresh} and {@link #unlink} are button presses. Every call runs on {@link RelayCalls}'
 * pool, never the render thread; state changes reach the one screen that watches through {@link #watch}, on the client
 * thread. Service key {@code discordlink-relay} (read on every call) points it at a fake.
 */
public final class DiscordLink {

    public enum State { UNKNOWN, WORKING, NOT_LINKED, CODE, LINKED, ERROR }

    public static final String SERVICE = "discordlink-relay";
    static final int POLL_S = 8;

    private static volatile State state = State.UNKNOWN;
    private static volatile String code = "";
    private static volatile long codeExpiresAt;
    private static volatile String discordName = "";
    private static volatile String error = "";
    /** Bumped by every start, refresh and unlink, so an older reply is dropped and an older poll loop stops. */
    private static volatile int generation;
    private static volatile Runnable watcher;

    private DiscordLink() {
    }

    public static State state() {
        return state;
    }

    public static String code() {
        return code;
    }

    /** Minutes left on the live code, at least 1 while it lives. */
    public static long codeMinutesLeft() {
        return Math.max(1, (codeExpiresAt - System.currentTimeMillis() + 59_999) / 60_000);
    }

    public static String discordName() {
        return discordName;
    }

    public static String error() {
        return error;
    }

    /** What the player types in Discord. */
    public static String command() {
        return "/link code:" + code;
    }

    /** The one screen to tell about changes (the card or the tour); it checks it is still open itself. */
    public static void watch(Runnable onChange) {
        watcher = onChange;
    }

    /** A new one-time code (replacing a live one); also learns whether the account is already linked. */
    public static void start() {
        int gen = ++generation;
        set(State.WORKING);
        RelayCalls.call(SERVICE, "POST", "/discord-link/start", new JsonObject()).thenAccept(r -> {
            if (gen != generation) {
                return;
            }
            if (!r.ok() || !r.body().has("code")) {
                fail(r);
                return;
            }
            code = r.body().get("code").getAsString();
            codeExpiresAt = r.body().get("expiresAt").getAsLong();
            readLinked(r.body());
            set(State.CODE);
            poll(gen);
        });
    }

    /** Asks the relay whether this account is linked (the Check Status button). */
    public static void refresh() {
        int gen = ++generation; // the newest press wins; an older poll loop stops
        State before = state;
        set(State.WORKING);
        RelayCalls.call(SERVICE, "GET", "/discord-link/status", null).thenAccept(r -> {
            if (gen != generation) {
                return;
            }
            if (!r.ok()) {
                fail(r);
                return;
            }
            boolean linked = readLinked(r.body());
            boolean stillCode = !linked && before == State.CODE && codeLive();
            set(linked ? State.LINKED : stillCode ? State.CODE : State.NOT_LINKED);
            if (stillCode) {
                poll(gen); // the old loop stopped with the generation bump
            }
        });
    }

    /** Deletes the link (and any live code) on the relay. */
    public static void unlink() {
        int gen = ++generation;
        set(State.WORKING);
        RelayCalls.call(SERVICE, "POST", "/discord-link/unlink", new JsonObject()).thenAccept(r -> {
            if (gen != generation) {
                return;
            }
            if (!r.ok()) {
                fail(r);
                return;
            }
            discordName = "";
            code = "";
            set(State.NOT_LINKED);
        });
    }

    private static boolean codeLive() {
        return !code.isEmpty() && System.currentTimeMillis() < codeExpiresAt;
    }

    /** Status polls while the code lives; stops once linked, expired, or a newer start/unlink began. */
    private static void poll(int gen) {
        CompletableFuture.delayedExecutor(POLL_S, TimeUnit.SECONDS).execute(() -> {
            if (gen != generation || state != State.CODE) {
                return;
            }
            if (!codeLive()) {
                code = "";
                set(State.NOT_LINKED);
                return;
            }
            RelayCalls.call(SERVICE, "GET", "/discord-link/status", null).thenAccept(r -> {
                if (gen != generation || state != State.CODE) {
                    return;
                }
                if (r.ok() && readLinked(r.body())) {
                    code = "";
                    set(State.LINKED);
                } else {
                    poll(gen); // a failed poll just tries again; the code's own expiry ends the loop
                }
            });
        });
    }

    /** Reads {@code linked} from a reply: true (and the name kept) when the account is linked. */
    private static boolean readLinked(JsonObject body) {
        if (body.has("linked") && body.get("linked").isJsonObject()) {
            JsonObject l = body.getAsJsonObject("linked");
            discordName = l.has("discordName") ? l.get("discordName").getAsString() : "";
            return true;
        }
        discordName = "";
        return false;
    }

    /** No HTTP answer reads "couldn't reach the relay", not a socket message; a relay answer keeps its own reason. */
    private static void fail(RelayCalls.Reply r) {
        String why = r.error();
        error = r.status() < 0 || why == null || why.isBlank() ? "couldn't reach the relay" : why;
        set(State.ERROR);
    }

    private static void set(State s) {
        state = s;
        Runnable w = watcher;
        Minecraft mc = Minecraft.getInstance();
        if (w != null && mc != null) {
            mc.execute(w);
        }
    }

    /** Test hook: back to "not checked yet", no code, no watcher, and any poll loop stopped. */
    public static void resetForTest() {
        generation++;
        state = State.UNKNOWN;
        code = "";
        codeExpiresAt = 0;
        discordName = "";
        error = "";
        watcher = null;
    }
}
