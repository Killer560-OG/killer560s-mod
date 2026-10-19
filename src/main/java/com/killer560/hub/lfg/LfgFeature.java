package com.killer560.hub.lfg;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.partyfinder.PartyFinderStatsApi;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom Party Finder (killer560's to-do item 1, 2026-10-18): "a custom party finder for things besides just dungeons but
 * still includes dungeons ... tabs for kuudra dungeons diana fishing. If someone clicks on it then it should have them
 * auto join the party. Have my client send them a request to join and theirs accept it."
 * <p>
 * <b>The flow.</b> A leader lists his party (the menu's Create, or automatically when he queues in Hypixel's dungeon Party
 * Finder - "dual list"). Every mod user sees it through the relay. A player clicks Join: his client sends a join request
 * to the relay (only that; nothing to Hypixel; the relay itself refuses a joiner under the listing's Cata Level, Secrets
 * or S+ PB, or one whose stats it does not have). The leader's client checks the request against the listing's
 * requirements again: under any of them, or with stats nobody could read, it is rejected (no Accept, the relay told
 * "declined"). One that meets them all is invited - by his click on Accept, or, with Auto Accept Requests on (the
 * default since 2026-10-18, when killer560 reported "the auto accept didnt work": he had waited 47 s on a request that
 * only a click could answer), by his client straight away. Either way {@code /party invite <joiner>} goes through
 * {@link LfgCommandGate} and the relay is told. The joiner's client sees the acceptance and, when Hypixel's own
 * invite line from that leader arrives, sends {@code /party accept <leader>} once.
 * <p>
 * <b>Hypixel's own "join request".</b> He asked for the joiner to send a join request instead of being invited, if Hypixel
 * has such a thing. This mod knows no Hypixel command for it, so it is not guessed at: invite + accept is the only path.
 * <p>
 * <b>Safety.</b> Every command goes through {@link LfgCommandGate} (names {@code [A-Za-z0-9_]{1,16}}, hard limits). An
 * invite needs a request the relay delivered for the leader's own live listing (the joiner's name stamped by the relay
 * from his verified token) that meets every checked requirement, and his click unless Auto Accept Requests is on; an
 * accept needs (1) a join request this player made
 * by clicking, (2) the relay saying that request's leader accepted it, and (3) Hypixel's anchored invite line naming that
 * same leader - and it is spent after one use. Requests from other mod users can at most fill the leader's inbox (eight)
 * and, with Auto Accept Requests on, draw invites no faster than the gate's limits. Never a movement packet, never a
 * camera change.
 * <p>
 * <b>When a listing goes</b> (default (c)): when the party fills (the relay drops a listing whose size reaches its max),
 * when Hypixel says the party ended (left, disbanded, kicked), on entering a dungeon, on leaving the server, or when he
 * unlists. Hypixel's "queue cancelled" line is not known yet: {@link #HYPIXEL_DELISTED} is an empty stub.
 */
public final class LfgFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-lfg");
    static final String CHAT = "Party Finder";

    /** Hypixel's party invite, one line of its framed message. The rank tag has no space and the name group no space or
     *  colon, so no player chat line ("[VIP] Bob: ...", "Party > ...") can match it. */
    static final Pattern INVITE = Pattern.compile("^(?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16}) has invited you to join their party!$");
    private static final String RANKED = "(?:\\[[A-Z+]{1,12}\\] )?[A-Za-z0-9_]{1,16}";
    /** The party ended for us (Odin's PartyUtils leave/disband lines, names bounded). */
    static final List<Pattern> PARTY_ENDED = List.of(
            Pattern.compile("^" + RANKED + " has disbanded the party!$"),
            Pattern.compile("^You have been kicked from the party by " + RANKED + "$"),
            Pattern.compile("^The party was disbanded because all invites expired and the party was empty\\.$"),
            Pattern.compile("^The party was disbanded because the party leader disconnected\\.$"),
            Pattern.compile("^You left the party\\.$"));
    /**
     * STUB - Hypixel's line for a Party Finder queue that was cancelled or delisted is not known (needs killer560's
     * screenshot). Empty, so nothing is matched; when known, add it here anchored on both ends.
     */
    static final List<Pattern> HYPIXEL_DELISTED = List.of();

    static final long HEARTBEAT_MS = 30_000L;
    static final long UPSERT_GAP_MS = 3_500L;
    static final long ME_POLL_MS = 3_000L;
    static final long LIST_POLL_MS = 5_000L;
    static final long ARM_MS = 60_000L;
    static final long INVITE_MEMORY_MS = 60_000L;
    static final long JOIN_CLICK_GAP_MS = 2_000L;

    /** The listing the player wants up (null: none); refreshed every {@link #HEARTBEAT_MS}. */
    private static JsonObject wanted;
    private static LfgListing myListing;
    private static long lastUpsertAt = 0;
    private static int lastSentSize = -1;
    private static long lastMeAt = 0;
    private static long lastListAt = 0;
    private static long lastJoinClickAt = 0;
    private static boolean screenOpen = false;
    private static LfgCategory screenTab = null;

    private static final Map<LfgCategory, List<LfgListing>> LISTINGS = new EnumMap<>(LfgCategory.class);
    private static final List<LfgListing.Request> INBOX = new ArrayList<>();
    /** Requests the leader answered here; hidden until the relay drops them, and never invited twice. */
    private static final Set<String> ANSWERED = new HashSet<>();
    /** Requests turned away here for being under the listing's requirements (or with stats nobody could read): shown as
     *  rejected, with no Accept, for {@link #REJECTED_SHOWN_MS}; the relay was told "declined". */
    private static final Map<String, Rejected> REJECTED = new LinkedHashMap<>();
    static final long REJECTED_SHOWN_MS = 60_000L;
    /** Auto Accept Requests: after the command gate turns an invite away (its spacing), wait this long before trying again. */
    static final long AUTO_RETRY_MS = 1_000L;
    private static long nextAutoAcceptAt = 0;

    /** What the leader's menu may do with a request: invite it, wait for the joiner's stats, or nothing at all. */
    public enum Verdict { OK, CHECKING, REJECTED }

    /** One row of the leader's inbox. {@code why} names the unmet requirement(s) for a rejected one. */
    public record InboxRow(LfgListing.Request request, Verdict verdict, String why) {
    }

    private record Rejected(LfgListing.Request request, String why, long at) {
    }
    /** Join requests THIS player made by clicking Join: id -> what it was for. Only these can arm an accept. */
    private static final Map<String, LfgListing> MY_REQUESTS = new LinkedHashMap<>();
    private static final Map<String, String> MY_REQUEST_STATE = new HashMap<>();
    /** Lower-case leader name -> until when his invite will be accepted (one use). */
    private static final Map<String, Long> ARMED = new HashMap<>();
    /** Lower-case leader name -> when his Hypixel invite line arrived (the invite can beat the relay poll). */
    private static final Map<String, Long> INVITES_SEEN = new HashMap<>();

    private static String status = "";
    private static int version = 0;
    /** Test hook: what happened, newest last. */
    private static final List<String> EVENTS = new ArrayList<>();

    private LfgFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(LfgFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LfgFeature", LfgFeature::tick));
    }

    // ---- state for the screen ----------------------------------------------------------------------------------------

    public static int version() {
        return version;
    }

    public static String status() {
        return status;
    }

    public static LfgListing myListing() {
        return myListing;
    }

    public static boolean isListed() {
        return wanted != null;
    }

    public static List<LfgListing> listings(LfgCategory c) {
        return List.copyOf(LISTINGS.getOrDefault(c, List.of()));
    }

    public static List<LfgListing.Request> inbox() {
        List<LfgListing.Request> out = new ArrayList<>();
        for (LfgListing.Request r : INBOX) {
            if (!ANSWERED.contains(r.id())) {
                out.add(r);
            }
        }
        return out;
    }

    /** The leader's inbox as the menu shows it: every waiting request with what may be done with it, then the ones turned
     *  away in the last minute. Only an {@link Verdict#OK} row gets an Accept button. */
    public static List<InboxRow> inboxRows() {
        List<InboxRow> out = new ArrayList<>();
        for (LfgListing.Request r : INBOX) {
            if (!ANSWERED.contains(r.id())) {
                out.add(verdict(r));
            }
        }
        long now = System.currentTimeMillis();
        for (Rejected x : REJECTED.values()) {
            if (now - x.at() < REJECTED_SHOWN_MS) {
                out.add(new InboxRow(x.request(), Verdict.REJECTED, x.why()));
            }
        }
        return out;
    }

    /** The joiner's stats against the leader's own listing: any unmet requirement, or stats that could not be read at
     *  all, is REJECTED; stats still being looked up are CHECKING; otherwise OK. */
    static InboxRow verdict(LfgListing.Request r) {
        LfgListing mine = myListing;
        if (mine == null) {
            return new InboxRow(r, Verdict.CHECKING, "");
        }
        List<LfgRequirements.Check> checks = LfgRequirements.check(mine.category(), mine.activity(), mine.reqs(), r.joiner());
        List<String> unmet = new ArrayList<>();
        for (LfgRequirements.Check c : checks) {
            if (c.result() == LfgRequirements.Result.UNMET) {
                unmet.add(c.plain());
            }
        }
        if (!unmet.isEmpty()) {
            return new InboxRow(r, Verdict.REJECTED, "under " + String.join(", ", unmet));
        }
        if (LfgRequirements.anyUnknown(checks)) {
            if (PartyFinderStatsApi.hasFailed(r.joiner())) {
                return new InboxRow(r, Verdict.REJECTED, "stats unknown");
            }
            return new InboxRow(r, Verdict.CHECKING, "checking stats");
        }
        return new InboxRow(r, Verdict.OK, "");
    }

    /** "pending" / "accepted" / "declined" for this player's own request on that listing, or null. */
    public static String myRequestStateFor(String listingId) {
        for (Map.Entry<String, LfgListing> e : MY_REQUESTS.entrySet()) {
            if (e.getValue().id().equals(listingId)) {
                return MY_REQUEST_STATE.getOrDefault(e.getKey(), "pending");
            }
        }
        return null;
    }

    public static List<String> events() {
        return List.copyOf(EVENTS);
    }

    /** The open screen says which tab it shows; listings are polled only while it is open. */
    public static void setScreen(boolean open, LfgCategory tab) {
        boolean opened = open && !screenOpen;
        screenOpen = open;
        if (tab != screenTab) {
            screenTab = tab; // null: the start screen, which shows no listings and polls none
            lastListAt = 0;
        }
        if (opened) {
            lastListAt = 0;
            lastMeAt = 0;
        }
    }

    private static void changed(String newStatus) {
        if (newStatus != null) {
            status = newStatus;
        }
        version++;
    }

    private static void event(String e) {
        EVENTS.add(e);
        if (EVENTS.size() > 200) {
            EVENTS.remove(0);
        }
        LOGGER.info("[PartyFinder] {}", e);
    }

    private static String self() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : client.player.getGameProfile().name();
    }

    // ---- listing ---------------------------------------------------------------------------------------------------

    /** The relay body for a listing. Size is filled in at send time from the party tracker. */
    public static JsonObject spec(LfgCategory category, String activity, String note, Map<String, Integer> reqs, int max,
                                  boolean fromHypixel) {
        JsonObject o = new JsonObject();
        o.addProperty("category", category.id);
        o.addProperty("activity", activity);
        o.addProperty("note", LfgConfig.clampNote(note));
        JsonObject r = new JsonObject();
        if (reqs != null) {
            reqs.forEach((k, v) -> {
                if (v != null && v > 0) {
                    r.addProperty(k, v);
                }
            });
        }
        o.add("reqs", r);
        o.addProperty("max", Math.max(2, Math.min(category.maxParty, max)));
        o.addProperty("source", fromHypixel ? "hypixel" : "mod");
        return o;
    }

    /** Puts (or replaces) the player's listing. */
    public static void list(JsonObject spec) {
        if (!LfgConfig.getInstance().isEnabled()) {
            changed("Custom Party Finder is off.");
            return;
        }
        wanted = spec;
        lastUpsertAt = 0;
        event("list " + spec.get("category").getAsString() + " " + spec.get("activity").getAsString()
                + " source " + spec.get("source").getAsString());
        upsertNow();
    }

    public static void unlist(String why) {
        boolean had = wanted != null || myListing != null;
        wanted = null;
        myListing = null;
        INBOX.clear();
        ANSWERED.clear();
        REJECTED.clear();
        lastSentSize = -1;
        if (had) {
            event("unlist: " + why);
            LfgRelay.delete();
            changed("Listing removed: " + why + ".");
        }
    }

    private static int partySize() {
        return 1 + PartyTracker.teammates().size();
    }

    private static void upsertNow() {
        if (wanted == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUpsertAt < UPSERT_GAP_MS) {
            return;
        }
        lastUpsertAt = now;
        JsonObject body = wanted.deepCopy();
        int size = Math.max(1, Math.min(partySize(), body.get("max").getAsInt()));
        body.addProperty("size", size);
        lastSentSize = size;
        JsonObject sent = wanted;
        LfgRelay.upsert(body).thenAccept(reply -> onClient(() -> onUpsert(sent, reply)));
    }

    static void onUpsert(JsonObject sent, LfgRelay.Reply reply) {
        if (sent == null || wanted != sent) {
            return; // replaced or removed meanwhile
        }
        if (!reply.ok()) {
            changed("Couldn't list: " + reply.error());
            return;
        }
        JsonElement l = reply.body().get("listing");
        if (l == null || l.isJsonNull()) {
            wanted = null;
            myListing = null;
            event("relay removed listing: " + LfgListing.strOr(reply.body(), "removed", "?"));
            changed("Party full - listing removed.");
            return;
        }
        LfgListing parsed = LfgListing.parse(l.getAsJsonObject());
        if (parsed != null) {
            myListing = parsed;
            changed("Listed: " + parsed.category().label + " " + parsed.activity() + ".");
        }
    }

    // ---- joining ---------------------------------------------------------------------------------------------------

    /** The Join button. @return null when the request went to the relay, else why not. */
    public static String requestJoin(LfgListing listing) {
        LfgConfig cfg = LfgConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "Custom Party Finder is off.";
        }
        String me = self();
        if (me != null && me.equalsIgnoreCase(listing.leader())) {
            return "That is your own listing.";
        }
        if (myRequestStateFor(listing.id()) != null) {
            return "Already asked.";
        }
        long now = System.currentTimeMillis();
        if (now - lastJoinClickAt < JOIN_CLICK_GAP_MS) {
            return "Wait a moment.";
        }
        if (cfg.isCheckRequirements()) {
            List<LfgRequirements.Check> checks = LfgRequirements.check(listing.category(), listing.activity(), listing.reqs(), me);
            boolean unknown = LfgRequirements.anyUnknown(checks);
            if (me != null && unknown) {
                PartyFinderStatsApi.requestHot(List.of(me));
            }
            if (LfgRequirements.anyUnmet(checks)) {
                StringBuilder sb = new StringBuilder("You don't meet:");
                for (LfgRequirements.Check c : checks) {
                    if (c.result() == LfgRequirements.Result.UNMET) {
                        sb.append(' ').append(c.label()).append(' ').append(c.need());
                    }
                }
                event("join refused (requirements) " + listing.leader());
                return sb.toString();
            }
            if (unknown) {
                // Unknown is not "met": the leader's client and the relay both turn it away, so say so here first.
                event("join refused (stats unknown) " + listing.leader());
                return me != null && PartyFinderStatsApi.hasFailed(me)
                        ? "Couldn't read your stats, so this party's requirements can't be checked."
                        : "Your stats are still loading - try again in a moment.";
            }
        }
        lastJoinClickAt = now;
        event("join request to " + listing.leader() + " (" + listing.activity() + ")");
        LfgRelay.join(listing.id()).thenAccept(reply -> onClient(() -> onJoinReply(listing, reply)));
        return null;
    }

    static void onJoinReply(LfgListing listing, LfgRelay.Reply reply) {
        if (!reply.ok()) {
            changed("Couldn't ask " + listing.leader() + ": " + reply.error());
            return;
        }
        JsonElement r = reply.body().get("request");
        String id = r != null && r.isJsonObject() ? LfgListing.str(r.getAsJsonObject(), "id") : null;
        if (id == null || !LfgListing.ID.matcher(id).matches()) {
            changed("The relay sent a bad answer.");
            return;
        }
        MY_REQUESTS.put(id, listing);
        MY_REQUEST_STATE.put(id, "pending");
        lastMeAt = 0;
        changed("Asked " + listing.leader() + " to join - waiting for them to accept.");
    }

    // ---- the leader's inbox ----------------------------------------------------------------------------------------

    /** The leader's Accept button. @return null when the invite went, else why not. */
    public static String accept(String requestId) {
        LfgListing.Request req = null;
        for (LfgListing.Request r : INBOX) {
            if (r.id().equals(requestId) && !ANSWERED.contains(r.id())) {
                req = r;
            }
        }
        if (req == null || myListing == null) {
            return "That request is gone.";
        }
        InboxRow v = verdict(req);
        if (v.verdict() == Verdict.REJECTED) {
            event("invite refused " + req.joiner() + ": " + v.why());
            return req.joiner() + " is " + v.why() + ".";
        }
        if (v.verdict() == Verdict.CHECKING) {
            return "Still checking " + req.joiner() + "'s stats.";
        }
        String refused = LfgCommandGate.invite(req.joiner());
        if (refused != null) {
            event("invite refused " + req.joiner() + ": " + refused);
            return refused;
        }
        ANSWERED.add(req.id());
        event("accepted " + req.joiner() + " (invite sent)");
        LfgRelay.respond(req.id(), true);
        changed("Invited " + req.joiner() + ".");
        return null;
    }

    public static void decline(String requestId) {
        for (LfgListing.Request r : INBOX) {
            if (r.id().equals(requestId) && ANSWERED.add(r.id())) {
                event("declined " + r.joiner());
                LfgRelay.respond(r.id(), false);
                changed("Declined " + r.joiner() + ".");
            }
        }
    }

    // ---- relay answers ---------------------------------------------------------------------------------------------

    /** A {@code /lfg/me} answer: the leader's inbox and the state of this player's own requests. */
    static void onMe(JsonObject body) {
        JsonElement l = body.get("listing");
        if (wanted != null) {
            myListing = l != null && l.isJsonObject() ? LfgListing.parse(l.getAsJsonObject()) : myListing;
        }
        List<LfgListing.Request> inbox = new ArrayList<>();
        JsonArray reqs = body.has("requests") && body.get("requests").isJsonArray() ? body.getAsJsonArray("requests") : new JsonArray();
        Set<String> live = new HashSet<>();
        for (JsonElement e : reqs) {
            if (!e.isJsonObject() || inbox.size() >= 8) {
                continue;
            }
            LfgListing.Request r = LfgListing.Request.parse(e.getAsJsonObject());
            if (r != null) {
                inbox.add(r);
                live.add(r.id());
            }
        }
        List<String> fresh = new ArrayList<>();
        Set<String> before = new HashSet<>();
        for (LfgListing.Request r : INBOX) {
            before.add(r.id());
        }
        for (LfgListing.Request r : inbox) {
            if (!before.contains(r.id()) && !ANSWERED.contains(r.id())) {
                fresh.add(r.joiner());
            }
        }
        INBOX.clear();
        if (wanted != null) {
            INBOX.addAll(inbox);
        }
        ANSWERED.retainAll(live);
        if (wanted != null && !fresh.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (LfgListing.Request r : inbox) {
                names.add(r.joiner());
            }
            PartyFinderStatsApi.requestHot(names);
            boolean auto = LfgConfig.getInstance().isAutoAcceptRequests();
            for (String name : fresh) {
                event("request from " + name);
                ModChat.send(CHAT, ModChat.value(name), ModChat.text(" wants to join - "),
                        ModChat.dim(auto ? "inviting if they meet your requirements." : "open /pf to accept."));
            }
        }

        JsonArray out = body.has("outgoing") && body.get("outgoing").isJsonArray() ? body.getAsJsonArray("outgoing") : new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonElement e : out) {
            if (!e.isJsonObject()) {
                continue;
            }
            LfgListing.Outgoing o = LfgListing.Outgoing.parse(e.getAsJsonObject());
            if (o == null || !MY_REQUESTS.containsKey(o.id())) {
                continue; // only a request this player made by clicking can arm anything
            }
            seen.add(o.id());
            LfgListing asked = MY_REQUESTS.get(o.id());
            String was = MY_REQUEST_STATE.put(o.id(), o.state());
            if (!o.state().equals(was)) {
                if (o.state().equals("accepted") && asked.leader().equalsIgnoreCase(o.leader())) {
                    arm(o.leader());
                } else if (o.state().equals("declined")) {
                    event("declined by " + o.leader());
                    changed(o.leader() + " declined your request.");
                }
            }
        }
        // A request the relay no longer has (expired) is forgotten.
        MY_REQUESTS.keySet().removeIf(id -> {
            boolean gone = !seen.contains(id);
            if (gone) {
                MY_REQUEST_STATE.remove(id);
            }
            return gone;
        });
        changed(null);
    }

    static void onListings(LfgCategory tab, JsonObject body) {
        List<LfgListing> parsed = new ArrayList<>();
        JsonArray arr = body.has("listings") && body.get("listings").isJsonArray() ? body.getAsJsonArray("listings") : new JsonArray();
        for (JsonElement e : arr) {
            if (e.isJsonObject() && parsed.size() < 200) {
                LfgListing l = LfgListing.parse(e.getAsJsonObject());
                if (l != null && (tab == null || l.category() == tab)) {
                    parsed.add(l);
                }
            }
        }
        LISTINGS.put(tab, parsed);
        changed(null);
    }

    // ---- accepting the leader's invite ------------------------------------------------------------------------------

    private static void arm(String leader) {
        String key = leader.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!LfgConfig.getInstance().isAutoAcceptInvite()) {
            event("accepted by " + leader + " (auto accept off)");
            changed(leader + " accepted - accept their party invite.");
            return;
        }
        ARMED.put(key, now + ARM_MS);
        event("accepted by " + leader + " - armed");
        changed(leader + " accepted - joining when their invite arrives.");
        Long seenAt = INVITES_SEEN.get(key);
        if (seenAt != null && now - seenAt < INVITE_MEMORY_MS) {
            acceptInvite(leader);
        }
    }

    private static void acceptInvite(String leader) {
        String key = leader.toLowerCase(Locale.ROOT);
        Long until = ARMED.get(key);
        if (until == null || System.currentTimeMillis() > until) {
            ARMED.remove(key);
            return;
        }
        ARMED.remove(key); // one use, whatever the gate says
        INVITES_SEEN.remove(key);
        String refused = LfgCommandGate.accept(leader);
        if (refused != null) {
            event("accept refused " + leader + ": " + refused);
            changed("Couldn't accept " + leader + "'s invite: " + refused);
            return;
        }
        event("accepted invite from " + leader);
        ModChat.send(CHAT, ModChat.text("Joining "), ModChat.value(leader), ModChat.text("'s party."));
        changed("Joining " + leader + "'s party.");
    }

    // ---- chat ------------------------------------------------------------------------------------------------------

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.indexOf("arty") < 0) {
            return; // every line below says "party"/"Party"
        }
        for (String line : plain.split("\n")) {
            onLine(line.trim());
        }
    }

    /** One line, colour codes stripped and trimmed. */
    public static void onLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        Matcher m = INVITE.matcher(line);
        if (m.matches()) {
            String leader = m.group(1);
            String key = leader.toLowerCase(Locale.ROOT);
            if (ARMED.containsKey(key)) {
                acceptInvite(leader);
            } else {
                INVITES_SEEN.put(key, System.currentTimeMillis());
                if (INVITES_SEEN.size() > 32) {
                    INVITES_SEEN.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > INVITE_MEMORY_MS);
                }
            }
            return;
        }
        if (wanted == null) {
            return;
        }
        for (Pattern p : PARTY_ENDED) {
            if (p.matcher(line).matches()) {
                unlist("the party ended");
                return;
            }
        }
        for (Pattern p : HYPIXEL_DELISTED) {
            if (p.matcher(line).matches()) {
                unlist("the Hypixel queue ended");
                return;
            }
        }
    }

    // ---- dual listing ----------------------------------------------------------------------------------------------

    /** Called by {@code autokick.QueuedFloorDetector} when Hypixel says the party was queued in the dungeon finder;
     *  {@code activity} is "F7", "M4" or "Entrance". Lists the same party here (default requirements from the Create
     *  form's saved Dungeons values, merged with what the Group Builder showed - see {@link #groupBuilderRequirements}). */
    public static void onHypixelDungeonQueued(String activity) {
        LfgConfig cfg = LfgConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isDualList() || !LfgCategory.DUNGEONS.activities.contains(activity)) {
            return;
        }
        LfgConfig.Draft d = cfg.draft(LfgCategory.DUNGEONS);
        Map<String, Integer> reqs = new LinkedHashMap<>(d.reqs);
        reqs.putAll(groupBuilderRequirements());
        list(spec(LfgCategory.DUNGEONS, activity, d.note, reqs, LfgCategory.DUNGEONS.maxParty, true));
        ModChat.send(CHAT, ModChat.text("Also listed in the Custom Party Finder: "), ModChat.value("Dungeons " + activity));
    }

    /**
     * STUB - the Group Builder's requirement items (Catacombs level / class level required) are not known: the menu's item
     * names and lore lines for them need killer560's screenshot. Until then nothing is read and the saved Create-form values
     * are used.
     */
    static Map<String, Integer> groupBuilderRequirements() {
        return Map.of();
    }

    // ---- tick ------------------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        if (client.player == null) {
            if (wanted != null || !MY_REQUESTS.isEmpty()) {
                unlist("left the server");
                MY_REQUESTS.clear();
                MY_REQUEST_STATE.clear();
                ARMED.clear();
                changed(null);
            }
            return;
        }
        if (!LfgConfig.getInstance().isEnabled()) {
            if (wanted != null) {
                unlist("Custom Party Finder turned off");
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (wanted != null) {
            if (DungeonState.isInDungeon()) {
                unlist("the run started");
                return;
            }
            int size = partySize();
            if (now - lastUpsertAt >= HEARTBEAT_MS || (size != lastSentSize && now - lastUpsertAt >= UPSERT_GAP_MS)) {
                upsertNow();
            }
            judgeInbox(now);
        }
        ARMED.entrySet().removeIf(e -> now > e.getValue());
        if ((wanted != null || !MY_REQUESTS.isEmpty()) && now - lastMeAt >= ME_POLL_MS) {
            lastMeAt = now;
            LfgRelay.me().thenAccept(reply -> onClient(() -> {
                if (reply.ok()) {
                    onMe(reply.body());
                }
            }));
        }
        if (screenOpen && screenTab != null && now - lastListAt >= LIST_POLL_MS) {
            lastListAt = now;
            LfgCategory tab = screenTab;
            LfgRelay.listings(tab).thenAccept(reply -> onClient(() -> {
                if (reply.ok()) {
                    onListings(tab, reply.body());
                } else {
                    changed("Couldn't load listings: " + reply.error());
                }
            }));
        }
    }

    /**
     * The leader's side of the requirements, every tick: a request under the listing's requirements (or whose stats could
     * not be read) is turned away - the relay is told "declined" and the menu shows it rejected, with no Accept; with Auto
     * Accept Requests on, one that meets them all is invited through {@link #accept}, so through the same command gate as
     * a click (its spacing and per-minute limits hold; a turned-away invite is tried again after {@link #AUTO_RETRY_MS}).
     */
    static void judgeInbox(long now) {
        if (myListing == null) {
            return;
        }
        REJECTED.values().removeIf(x -> now - x.at() >= REJECTED_SHOWN_MS);
        boolean auto = LfgConfig.getInstance().isAutoAcceptRequests();
        List<String> checking = new ArrayList<>();
        for (LfgListing.Request r : List.copyOf(INBOX)) {
            if (ANSWERED.contains(r.id())) {
                continue;
            }
            InboxRow v = verdict(r);
            switch (v.verdict()) {
                case REJECTED -> {
                    ANSWERED.add(r.id());
                    REJECTED.put(r.id(), new Rejected(r, v.why(), now));
                    event("rejected " + r.joiner() + ": " + v.why());
                    LfgRelay.respond(r.id(), false);
                    ModChat.send(CHAT, ModChat.value(r.joiner()), ModChat.text(" can't join - "), ModChat.dim(v.why() + "."));
                    changed(null);
                }
                case CHECKING -> checking.add(r.joiner());
                case OK -> {
                    if (auto && now >= nextAutoAcceptAt) {
                        String refused = accept(r.id());
                        if (refused == null) {
                            ModChat.send(CHAT, ModChat.text("Invited "), ModChat.value(r.joiner()),
                                    ModChat.dim(" (meets your requirements)."));
                        } else {
                            nextAutoAcceptAt = now + AUTO_RETRY_MS;
                        }
                    }
                }
            }
        }
        if (!checking.isEmpty()) {
            PartyFinderStatsApi.requestHot(checking);
        }
    }

    /** Test hook: one pass of the leader's inbox judging, as the tick does it. */
    public static void judgeInboxForTest() {
        judgeInbox(System.currentTimeMillis());
    }

    private static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    /** Feeds a {@code /lfg/me} body as if the relay had answered (testkit 1018-pf-*). */
    public static void onMeForTest(String json) {
        onMe(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    public static void onListingsForTest(String category, String json) {
        onListings(LfgCategory.fromId(category), com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    /** Feeds a {@code /lfg/join} answer for the listing with that id on any tab. */
    public static void onJoinReplyForTest(String listingId, String json) {
        for (List<LfgListing> list : LISTINGS.values()) {
            for (LfgListing l : list) {
                if (l.id().equals(listingId)) {
                    onJoinReply(l, new LfgRelay.Reply(200, com.google.gson.JsonParser.parseString(json).getAsJsonObject(), null));
                }
            }
        }
    }

    /** Feeds a {@code POST /lfg/listing} answer for the listing currently wanted. */
    public static void onUpsertForTest(String json) {
        onUpsert(wanted, new LfgRelay.Reply(200, com.google.gson.JsonParser.parseString(json).getAsJsonObject(), null));
    }

    public static String wantedForTest() {
        return wanted == null ? null : wanted.toString();
    }

    public static void resetForTest() {
        wanted = null;
        myListing = null;
        lastUpsertAt = 0;
        lastSentSize = -1;
        lastMeAt = 0;
        lastListAt = 0;
        lastJoinClickAt = 0;
        LISTINGS.clear();
        INBOX.clear();
        ANSWERED.clear();
        REJECTED.clear();
        nextAutoAcceptAt = 0;
        screenTab = null;
        MY_REQUESTS.clear();
        MY_REQUEST_STATE.clear();
        ARMED.clear();
        INVITES_SEEN.clear();
        EVENTS.clear();
        status = "";
        version++;
    }
}
