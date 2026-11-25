package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McItems;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.util.ModNet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * NEU Classic's Bingo page (its BingoPage over pv_bingo_tab), shown only for a Bingo profile: "Collected Points" and
 * "Personal Goals" at 22, 19 and 22, 31; the event's goals five to a row from 231, 46 (24 apart): a community goal as an
 * emerald block once every tier is reached, else an iron block; a personal goal as lime dye once done, else paper.
 * Without data for the current event: the red "No Bingo data for current event!" line. The player's events come from
 * Hypixel's skyblock/bingo, the goals from the keyless resources/skyblock/bingo (renewed every 2 minutes, as the original).
 * Built off the render thread; the draw only reads the built cells.
 */
final class NeuBingoPage {
    private static final String GOALS_URL = ModNet.url("hypixel", "https://api.hypixel.net/v2/resources/skyblock/bingo");
    private static final long GOALS_TTL = 120_000;
    private static final ItemStack PAPER = new ItemStack(Items.PAPER), DYE = new ItemStack(McItems.LIME_DYE),
            IRON = new ItemStack(Items.IRON_BLOCK), EMERALD = new ItemStack(Items.EMERALD_BLOCK);

    record Cell(int x, int y, int hoverY, ItemStack icon, List<Component> tip) {
    }

    static final class Model {
        boolean noData;
        String points, goals;
        final List<Cell> cells = new ArrayList<>();
    }

    private static volatile JsonObject goalsJson;
    private static volatile long goalsAt;
    private static CompletableFuture<JsonObject> goalsLoad;

    private final ProfileViewerScreen s;
    private SbProfile modelFor;
    private JsonObject modelGoals;
    private CompletableFuture<Model> model;

    NeuBingoPage(ProfileViewerScreen s) {
        this.s = s;
    }

    /** The goals list, fetched again once it is 2 minutes old (null until the first answer). */
    private static synchronized JsonObject goals() {
        long now = System.currentTimeMillis();
        if ((goalsJson == null || now - goalsAt > GOALS_TTL) && (goalsLoad == null || goalsLoad.isDone())) {
            goalsAt = now;
            goalsLoad = ProfileViewerApi.getKeylessJson(GOALS_URL).thenApply(j -> {
                if (j != null && j.has("goals") && j.get("goals").isJsonArray()) goalsJson = j;
                return j;
            });
        }
        return goalsJson;
    }

    void draw(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        JsonObject goals = goals();
        if (goals == null) return; // the original draws nothing until its goals arrive
        if (modelFor != p || modelGoals != goals || model == null) {
            modelFor = p;
            modelGoals = goals;
            model = s.bingoAux(p.member.toString()).handle((r, err) -> r == null ? null : r.data())
                    .thenApplyAsync(info -> build(info, goals), ProfileViewerApi.EXECUTOR);
        }
        Model m = model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (m == null) return;
        int l = s.panelX, t = s.panelY;
        if (m.noData) {
            String text = "§cNo Bingo data for current event!";
            g.text(s.font(), text, l + 215 - s.font().width(text) / 2, t + 97, 0xFFFFFFFF, true);
            return;
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PvStyleChrome.NEU_BINGO_TAB, l, t, PvStyleChrome.NEU_W, PvStyleChrome.NEU_H);
        List<Component> tip = null;
        for (Cell c : m.cells) {
            g.item(c.icon, l + c.x, t + c.y);
            if (c.tip != null && mx >= l + c.x && mx < l + c.x + 24 && my >= t + c.hoverY && my <= t + c.hoverY + 24 && !s.dropdownOpen) {
                tip = c.tip;
            }
        }
        g.text(s.font(), m.points, l + 22, t + 19, 0xFFFFFFFF, true);
        g.text(s.font(), m.goals, l + 22, t + 31, 0xFFFFFFFF, true);
        if (tip != null) s.pendingTooltip = tip;
    }

    /** {@code info} is skyblock/bingo's answer (null = none); {@code goals} resources/skyblock/bingo's. */
    static Model build(JsonObject info, JsonObject goals) {
        Model m = new Model();
        JsonArray events = info == null || !info.has("events") || !info.get("events").isJsonArray() ? null : info.getAsJsonArray("events");
        JsonObject last = events == null || events.isEmpty() ? null : SbProfile.asObj(events.get(events.size() - 1));
        int current = goals.has("id") ? goals.get("id").getAsInt() : -1;
        if (last == null || !last.has("key") || last.get("key").getAsInt() != current) {
            m.noData = true;
            return m;
        }
        Set<String> done = new HashSet<>();
        JsonArray completed = last.has("completed_goals") && last.get("completed_goals").isJsonArray() ? last.getAsJsonArray("completed_goals") : new JsonArray();
        for (JsonElement e : completed) done.add(e.getAsString());
        int col = 0, row = 0, dx = 0, dy = 0;
        for (JsonElement e : goals.getAsJsonArray("goals")) {
            JsonObject goal = SbProfile.asObj(e);
            if (goal == null) continue;
            boolean community = goal.has("tiers") && goal.get("tiers").isJsonArray();
            boolean reached = community ? communityTier(goal) == goal.getAsJsonArray("tiers").size()
                    : done.contains(goal.has("id") ? goal.get("id").getAsString() : "");
            // The original never resets its one-pixel nudges: once a community or done goal sets one, every later cell keeps it.
            if (community || reached) dx = -1;
            if (community) dy = -1;
            int x = 231 + 24 * col + dx, y = 46 + 24 * row + dy;
            ItemStack icon = community ? (reached ? EMERALD : IRON) : reached ? DYE : PAPER;
            // The original tests the hover from one pixel higher for a community goal (y - 1).
            m.cells.add(new Cell(x, y, community ? y - 1 : y, icon, tooltip(goal, reached, community)));
            if (++col == 5) {
                col = 0;
                row++;
            }
        }
        m.points = "§bCollected Points: §f" + (last.has("points") ? last.get("points").getAsInt() : 0);
        m.goals = done.size() == 20 ? "§bPersonal Goals: §620/20" : "§bPersonal Goals: §f" + done.size() + "§6/§f20";
        return m;
    }

    private static int communityTier(JsonObject goal) {
        long progress = goal.has("progress") ? goal.get("progress").getAsLong() : 0;
        int tier = 0;
        for (JsonElement t : goal.getAsJsonArray("tiers")) {
            if (progress < t.getAsLong()) break;
            tier++;
        }
        return tier;
    }

    private static List<Component> tooltip(JsonObject goal, boolean reached, boolean community) {
        List<String> lines = new ArrayList<>();
        String name = goal.has("name") ? goal.get("name").getAsString() : "";
        if (community) {
            JsonArray tiers = goal.getAsJsonArray("tiers");
            int total = tiers.size(), tier = communityTier(goal);
            double progress = goal.has("progress") ? goal.get("progress").getAsLong() : 0;
            // As the original: the target is the LAST tier while any is left, else the reached one.
            double next = total == 0 ? 1 : tier < total ? tiers.get(total - 1).getAsLong() : tiers.get(tier - 1).getAsLong();
            int pct = (int) Math.min(100, Math.round(progress / next * 100));
            lines.add("§a" + name + " " + tier);
            lines.add("§8Community Goal");
            lines.add("");
            lines.add("§7Progress to " + name + " " + (tier < total ? tier + 1 : total) + ": §e" + pct + "§6%");
            lines.add(bar(progress, next) + "§e " + PvStyleChrome.neuShort(progress) + "§6/§e" + PvStyleChrome.neuShort(next));
            lines.add("");
            lines.add("§8§oCommunity Goals are");
            lines.add("§8§ocollaborative - anyone with a");
            lines.add("§8§oBingo profile can help to reach");
            lines.add("§8§othe goal!");
            lines.add("");
            lines.add("§8§oThe more you contribute");
            lines.add("§8§otowards the goal, the more you");
            lines.add("§8§owill be rewarded");
            if (reached) {
                lines.add("");
                lines.add("§aGOAL REACHED");
            }
        } else {
            lines.add("§a" + name);
            lines.add("§8Personal Goal");
            lines.add("");
            lines.add(goal.has("lore") ? goal.get("lore").getAsString() : "");
            lines.add("");
            lines.add("§7Reward");
            lines.add("§61 Bingo Point");
            lines.add("");
            lines.add(reached ? "§aGOAL REACHED" : "§cYou have not reached this goal!");
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(Component.literal(line));
        return List.copyOf(out);
    }

    /** The original's 20 dashes, dark green up to one past the filled count, then gray. */
    private static String bar(double progress, double goal) {
        int filled = progress / goal >= 1 ? 20 : (int) Math.round(progress / goal * 20);
        StringBuilder b = new StringBuilder("§2");
        for (int i = 0; i < 20; i++) {
            b.append('-');
            if (i > filled) b.append("§7");
        }
        return b.toString();
    }
}
