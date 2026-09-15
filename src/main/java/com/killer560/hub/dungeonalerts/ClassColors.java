package com.killer560.hub.dungeonalerts;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class Colors - teammates colored by dungeon class, using this mod's shared {@link DungeonClass} colors.
 * <ul>
 * <li>Class detection: NoammAddons (26.1.2 upstream) {@code utils/dungeons/DungeonListener.kt}'s tab-list regex
 * {@code ^\[\d+] (?:\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\((\w+)(?: (\w+))?\)$} on each listed entry's unformatted
 * display name; "DEAD" keeps the previously known class (same as Noamm).
 * <li>Nametags: Noamm {@code features/impl/dungeon/TeammateESP.kt} "Show Teammate Name" - "§e[C§e] " + class
 * color + name, drawn see-through at entity y + bbHeight + 0.7 + distance * 0.015, scale max(distance * 0.12, 1),
 * for every teammate except yourself. (Noamm also hides the vanilla nametag via a mixin - not done here.)
 * <li>Tab list: the teammate's name inside their tab entry is recolored (DungeonAlertsTabOverlayMixin -&gt;
 * {@link #recolorTabName}). This half is this mod's own; Noamm's ClassColors only defines the colors.
 * </ul>
 */
public final class ClassColors {

    private static final Pattern TAB_REGEX = Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");

    private static final Map<String, DungeonClass> CLASSES = new HashMap<>();
    private static int tickCounter = 0;
    private static String lastLogged = "";

    private ClassColors() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter < 10) {
                return;
            }
            tickCounter = 0;
            DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
            boolean needed = cfg.classColorsEnabled || cfg.ragEnabled && cfg.ragM7Alert;
            if (!needed || client.getConnection() == null || !DungeonState.isInDungeon()) {
                return;
            }
            for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                if (display != null) {
                    parse(ChatFormatting.stripFormatting(display.getString()));
                }
            }
            String summary = CLASSES.toString();
            if (!summary.equals(lastLogged)) {
                lastLogged = summary;
                DungeonAlertsFeature.LOGGER.info("[DungeonAlerts] Teammate classes: {}", summary);
            }
        });
    }

    /** @return [name, class] if the line is a dungeon teammate tab entry. */
    private static String[] parse(String plain) {
        if (plain == null) {
            return null;
        }
        Matcher m = TAB_REGEX.matcher(plain.trim());
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1);
        String clazz = m.group(2);
        if (!"DEAD".equalsIgnoreCase(clazz)) {
            DungeonClass parsed = "Berserk".equalsIgnoreCase(clazz) ? DungeonClass.BERSERKER : DungeonClass.byName(clazz);
            if (parsed != null) {
                CLASSES.put(name, parsed);
            } else {
                CLASSES.remove(name);
            }
        }
        return new String[]{name, clazz};
    }

    static void onWorldChange() {
        CLASSES.clear();
        lastLogged = "";
    }

    static DungeonClass selfClass() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : CLASSES.get(client.player.getGameProfile().name());
    }

    /** Called from the PlayerTabOverlay#getNameForDisplay mixin. Returns {@code original} untouched unless this
     *  entry is a known teammate - then the name text is re-colored with the class color. */
    public static Component recolorTabName(PlayerInfo info, Component original) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (original == null || info == null || !cfg.classColorsEnabled || !cfg.classColorsTab || !DungeonState.isInDungeon()
                || info.getTabListDisplayName() == null) {
            return original;
        }
        Matcher m = TAB_REGEX.matcher(com.killer560.hub.util.ChatObserver.strip(info.getTabListDisplayName()).trim());
        if (!m.matches()) {
            return original;
        }
        String name = m.group(1);
        DungeonClass clazz = CLASSES.get(name);
        if (clazz == null) {
            return original;
        }
        TextColor color = TextColor.fromRgb(clazz.color() & 0xFFFFFF);
        MutableComponent rebuilt = Component.empty();
        boolean[] done = {false};
        original.visit((style, text) -> {
            int idx = done[0] ? -1 : indexOfName(text, name);
            if (idx < 0) {
                rebuilt.append(Component.literal(text).withStyle(style));
            } else {
                done[0] = true;
                if (idx > 0) {
                    rebuilt.append(Component.literal(text.substring(0, idx)).withStyle(style));
                }
                rebuilt.append(Component.literal(name).withStyle(style.withColor(color)));
                String rest = text.substring(idx + name.length());
                if (!rest.isEmpty()) {
                    rebuilt.append(Component.literal(rest).withStyle(style));
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return done[0] ? rebuilt : original;
    }

    /** Whole-word match so "Bob" doesn't match inside "Bobby". */
    private static int indexOfName(String text, String name) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(name, from);
            if (idx < 0) {
                return -1;
            }
            int end = idx + name.length();
            boolean startOk = idx == 0 || !isNameChar(text.charAt(idx - 1));
            boolean endOk = end >= text.length() || !isNameChar(text.charAt(end));
            if (startOk && endOk) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static void onWorldRender(LevelRenderContext context) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.classColorsEnabled || !cfg.classColorsNametags || CLASSES.isEmpty() || client.level == null
                || client.player == null || !DungeonState.isInDungeon()) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 self = client.player.getPosition(partial);
        String selfName = client.player.getGameProfile().name();
        for (Player player : client.level.players()) {
            String name = player.getGameProfile().name();
            DungeonClass clazz = CLASSES.get(name);
            if (clazz == null || name.equals(selfName) || player.getUUID().version() != 4) {
                continue;
            }
            Vec3 pos = player.getPosition(partial);
            double distance = pos.distanceTo(self);
            float scale = (float) Math.max(distance * 0.12, 1.0);
            Component text = Component.literal("[" + clazz.displayName().charAt(0) + "] ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(clazz.color() & 0xFFFFFF))));
            DungeonAlertsFeature.renderWorldText(context, text, pos.x,
                    pos.y + player.getBbHeight() + 0.7 + distance * 0.015, pos.z, scale);
        }
    }

}
