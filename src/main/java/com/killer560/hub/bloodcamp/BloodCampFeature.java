package com.killer560.hub.bloodcamp;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.compat.McCompat;

/**
 * Blood Camp - killer560's request: "use noamm's blood mob tracer thing it already has, then add a
 * triggerbot option so if i am looking at the right hitbox as the timer expires then it will left click
 * on the mob." Real mechanic and every constant below ported directly from Noamm's own confirmed,
 * compiling {@code BloodCamp.kt} (cloned reference, 2026-09-14) - the Blood Room "Blood Camp" mechanic,
 * present on every floor and skipped once the boss fight starts: a real {@code Zombie} wearing a real specific player-head skin is the Watcher, and real
 * {@code ArmorStand} entities wearing one of a real fixed set of player-head skins are the blood mobs,
 * both identified by their head skull's real texture value (not a guess - the exact real base64 values
 * in {@link #WATCHER_SKULL_TEXTURES}/{@link #MOB_SKULL_TEXTURES} are copied verbatim from that source).
 * <p>
 * A blood mob doesn't respawn as a new entity - the SAME entity periodically repositions, tracked via
 * real {@code ClientboundMoveEntityPacket} deltas and extrapolated forward (matching Noamm's own real
 * math) to predict where it's about to settle ({@code endVector}) before it visibly arrives there, and a
 * real countdown (also Noamm's own real formula) estimates when it'll be vulnerable again.
 * <p>
 * Extras killer560 asked for beyond Noamm's own real feature: a Trigger Bot (real left-click once the
 * countdown expires AND the player's real crosshair is on that exact spot - see {@link #aimedAt} for why
 * "on that spot" is ray-traced here rather than read off {@code Minecraft#hitResult}), a Kill Popup for the
 * Watcher's own move schedule ({@link BloodCampMoveTimer}), a Trigger Offset of -10..10 ticks from the kill
 * timer for exactly when that click fires, one click per mob unless it missed (then it is retried), a
 * spawn-overlay-only mode with no clicking at all, and an "aura" ({@link BloodCampAim}) that turns him toward
 * the mob due next with a human-looking mouse movement. Both only act inside the Blood Room. The aura's rotation math follows this mod's own
 * standing rule: Hypixel tracks a real player's yaw as a continuously running, uncapped value, so this
 * never wraps/clamps the stored rotation at 0-360 - it only ever computes a bounded DELTA (via
 * {@link Mth#wrapDegrees}) and adds that onto the player's existing real yaw, exactly the same way a
 * real 10-times-spun player's yaw would keep climbing past 3600 instead of resetting.
 */
public final class BloodCampFeature {

    static final Logger LOGGER = ModLog.get("killer560smod-bloodcamp");

    // Real base64 skull "textures" property values, copied verbatim from Noamm's own confirmed source -
    // not re-derived or guessed. Two of the real mob skulls have no attached player profile at all (a
    // bare MineSkin upload), so matching on the exact texture STRING (not a profile UUID) is the only way
    // to catch every real one of them.
    private static final Set<String> WATCHER_SKULL_TEXTURES = Set.of(
            "ewogICJ0aW1lc3RhbXAiIDogMTY5NzMwOTQxNzI1NiwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYxNTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2MmI2ZmI0YjhiNTg2ZGM0Y2RmODAzYjA0NDRkOWI0MWQyNDVjZGY2NjhkYWIzOGZhNmMwNjRhZmU4ZTQ2MSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjM1MjMyMiwKICAicHJvZmlsZUlkIiA6ICI3MmY5MTdjNWQyNDU0OTk0YjlmYzQ1YjVhM2YyMjIzMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGF0X0d1eV9Jc19NZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNzM5ZDdmNGU2NmE3ZGIyZWE2Y2Q0MTRlNGM0YmE0MWRmN2E5MjQ1NWM5ZmM0MmNhYWIwMTQ2NjVjMzY3YWQ1IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjI5MjgzNiwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjZlMWU3ZWQzNjU4NmMyZDk4MDU3MDAyYmMxYWRjOTgxZTI4ODlmN2JkN2I1YjM4NTJiYzU1Y2M3ODAyMjA0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTY5NzIzODQ0NjgxMiwKICAicHJvZmlsZUlkIiA6ICJmMjc0YzRkNjI1MDQ0ZTQxOGVmYmYwNmM3NWIyMDIxMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJIeXBpZ3NlbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80Y2VjNDAwMDhlMWMzMWMxOTg0ZjRkNjUwYWJiMzQxMGYyMDM3MTE5ZmQ2MjRhZmM5NTM1NjNiNzM1MTVhMDc3IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjAwOTg2NywKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3ZGQxOGI1OTgzYTc2N2U1NTZkYzY0NDI0YWY0YjlhYmRiNzVkNGM5ZThiMDk3ODE4YWZiYzQzMWJmMGUwOSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNTkyNDIwNSwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mNWYwZDc4ZmUzOGQxZDdmNzVmMDhjZGNmMmExODU1ZDZkYTAzMzdlMTE0YTNjNjNlM2JmM2M2MThiYzczMmIwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTU1MDkyNjM2MSwKICAicHJvZmlsZUlkIiA6ICI0ZDcwNDg2ZjUwOTI0ZDMzODZiYmZjOWMxMmJhYjRhZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzaXJGYWJpb3pzY2hlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzUxOTY3ZGI1ZTMxOTk5MTYyNTIwMjE5MDNjZjRlOTk1MmVmN2NlYzIyMGZhYWNhMWJhNzliYWZlNTkzOGJkODAiCiAgICB9CiAgfQp9",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIxMjc1NSwKICAicHJvZmlsZUlkIiA6ICI2NGRiNmMwNTliOTk0OTM2YTY0M2QwODEwODE0ZmJkMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVTaWx2ZXJEcmVhbXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWZkNjFlODA1NWY2ZWU5N2FiNWI2MTk2YThkN2VjOTgwNzhhYzM3ZTAwMzc2MTU3YjZiNTIwZWFhYTJmOTNhZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
            "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIzOTU4NiwKICAicHJvZmlsZUlkIiA6ICJhYWZmMDUwYTExOTk0NzM1YjEyNDVlNDk0MGFlZjY4NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJMYXN0SW1tb3J0YWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTVjMWRjNDdhMDRjZTU3MDAxYThiNzI2ZjAxOGNkZWY0MGI3ZWE5ZDdiZDZkODM1Y2E0OTVhMGVmMTY5Zjg5MyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    );

    private static final Set<String> MOB_SKULL_TEXTURES = Set.of(
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwNjQwNTAsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhNzk4NjBhY2E3OTk0MDdjMGZhYTEwYjFiYmNmNDI5OThmYWQ0ZWJjZjMxZDdhMjE0MTgwODI2YjRhYzk0ZTEifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDExODY2MzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzQ3NzQ4NzExOTBjODc4YzlhMmM0NDk2YzFlMTAyNTdjNmM0ZWExMzgwN2Q3MmMxNWQ3YWM2YWIzYTdhOWE4ZGMifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDAyMDM1NzMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Y0NjI0YTlhOGM2OWNhMjA0NTA0YWJiMDQzZDQ3NDU2Y2Q5YjA5NzQ5YTM2MzU3NDYyMzAzZjI3NmEyMjlkNCJ9fX0=",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDExNDUyMjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M5MTllNWI4ZDU2ZjA2MmEyMWQyMjRkZTE0YWY3NzFlMmY1NWQwOWI1OWU3YjA5OWQwOWRhYTU3NTQwYjc5Y2YiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA1MzgzODIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2E4OWY2MzAzYWY4NTg3NzYxMDkxMmRjMDRiOGIxZTg5NzI0NzUyZjBhN2VlYTA1YWI2NTQ3ZTIyODE3OWMwNmYiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5ODk1NTgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY3MjM3ZWRkYWViZGJkYWFjZmE5MTI4ODU1NjBjY2RjNjVkYTkzYjRjM2Q1MTM1MzI4NjhlYzIzYmI1YjQ0OCJ9fX0=",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA0OTUwMjgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZmMTg0YzE5ZTcyNTYyM2QzMjgyOGEwYTRlNzQxZTg2ZjEzNWFjNjNkYmM4MjhmZjNjODQ2ODMzOGYzNjgzYiJ9fX0=",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwMzA3NjUsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVjY2NkNTNmNTE5MWMyOWE5ZGM4ZjAxNzBmYmRjNGU1OWU2NjQ3NmFhZTMzZGUyN2I0NjhmMWRlMWI3Y2YzYjIifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5MTc4NzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I1YmE3NmUwMmNhYjcyZmE3ZDhhYzU0Y2VlYzg0OTk3NmFiMGIwMGEwMTA2OGQ2OGMyNjY3NjZiZjcwYzM5OTcifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA3Njk2MTQsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FhMjNjOGNkZTI5NDNjODQyNDlkZTgzNTFiYzM1NDBiZTVmOGFmYWFiYThiMmNiMDMyZmM1YWNhZDc4YTI2OWIifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA4MTg4MDMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkxNzFmMzViOGY1MDgxNDJiZDhjNjU0MTdkMGYzMjQxNTNhYjkxNDc3MzllZTRkMTBkZWE3MzNjYzgwZWFhMjAifX19",
            "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5NTY0MjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkMTJiMmFkZTQxM2E2Y2Q3Y2NhM2M5NWU5NjFiYTlmMGFlNzE2NWZhNDFmYzdiNWQ1ZjA5NGEwMTI0MGM2MDkifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZjM2UzMWNmYzY2NzMzMjc1YzQyZmNmYjVkOWE0NDM0MmQ2NDNiNTVjZDE0YzljNzdkMjczYTIzNTIifX19",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzE2OTIxMSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODQyMWJhNWI4ZTM1NzNlZjk3YmViNWI0MGUxNWQxNWIyMGYzMDYzMWM0YzUzMzBjM2RlZGEzMDQ3ZGYwZTkyIgogICAgfQogIH0KfQ==",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzExMjUwMCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQyMjc3MmY3NjkwNDVmZGM1YmU4MTlhZDY4YjAxYTk3YWMwNGM2MDg4NmQyY2E3YWZlZTM5YjI4MmY3YTM4MyIKICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzM4Njc5NCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQ2N2Y5N2Q3ZjgyMTcyOWJlYjM0YTgyYzNmMTM1OTJiNDA0MzlmZTUyNDhlNzI1NzZmZGU3YWExODBiZjc3IgogICAgfQogIH0KfQ==",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzIxNTkwNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmIzOTczYTc1MmIyNGEyZjNhYmIwMDM0MjdmNmRiZTZjYTNhNjFkYjBhMWJjZjM1MWM2ZWFiMjdlYzI3ZTUwIgogICAgfQogIH0KfQ==",
            "eyJ0aW1lc3RhbXAiOjE1NzQ0MTkzMTAxNjQsInByb2ZpbGVJZCI6Ijc1MTQ0NDgxOTFlNjQ1NDY4Yzk3MzlhNmUzOTU3YmViIiwicHJvZmlsZU5hbWUiOiJUaGFua3NNb2phbmciLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzEyNzE2ZWNiZjViOGRhMDBiMDVmMzE2ZWM2YWY2MWU4YmQwMjgwNWIyMWViOGU0NDAxNTE0NjhkYzY1NjU0OWMifX19",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzAyODAxNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzI2MDMyNTE3MWE3YmE4NDYwODMwYzBlZWE1MTVjNzU3YTY2NWU1YjE2YTE0MjA3YmExYTMxODI3NTJiZWU4NyIKICAgIH0KICB9Cn0=",
            "ewogICJ0aW1lc3RhbXAiIDogMTU5NTQyODIyMDAyMCwKICAicHJvZmlsZUlkIiA6ICJkYTQ5OGFjNGU5Mzc0ZTVjYjYxMjdiMzgwODU1Nzk4MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOaXRyb2hvbGljXzIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjJkOGZkM2FhNTYxN2IxZGFjMGFhZTljODFmNmRkNzBhZDkzYTU5OTQyZjQ2MGQyN2U0ZDU1YTVjYjg5MThlOCIKICAgIH0KICB9Cn0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==",
            "ewogICJ0aW1lc3RhbXAiIDogMTU4OTc5MzA2ODgzOSwKICAicHJvZmlsZUlkIiA6ICIyYzEwNjRmY2Q5MTc0MjgyODRlM2JmN2ZhYTdlM2UxYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOYWVtZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83ZGU3YmJiZGYyMmJmZTE3OTgwZDRlMjA2ODdlMzg2ZjExZDU5ZWUxZGI2ZjhiNDc2MjM5MWI3OWE1YWM1MzJkIgogICAgfQogIH0KfQ==",
            "ewogICJ0aW1lc3RhbXAiIDogMTU5ODk3NzI1OTM1NywKICAicHJvZmlsZUlkIiA6ICJlNzkzYjJjYTdhMmY0MTI2YTA5ODA5MmQ3Yzk5NDE3YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVfSG9zdGVyX01hbiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMTAwN2M1YjcxMTRhYmVjNzM0MjA2ZDRmYzYxM2RhNGYzYTBlOTlmNzFmZjk0OWNlZGFkYzk5MDc5MTM1YTBiIgogICAgfQogIH0KfQ=="
    );

    /** Boxes are grown this much before the crosshair ray is tested against them - the same inflation
     *  {@code terminalaura.TerminalStands} uses for its own triggerbot. */
    private static final double AIM_INFLATE = 0.1;
    /** How far from a predicted spawn a real entity may be and still count as "the mob that spawned there". */
    private static final double TARGET_SEARCH_RADIUS = 2.0;
    /** The Trigger Bot only fires inside this many ticks past its due moment; after that the prediction is
     *  stale and a click would just be a random swing at whatever is still standing there. */
    private static final double TRIGGER_WINDOW_TICKS = 20.0;
    /**
     * How far the crosshair test reaches for "looking at the spawn hitbox". It used to be the attack reach (3 blocks),
     * so standing further than 3 blocks from where a mob lands - which is where anyone camps blood from - the Trigger
     * Bot never saw his aim and never clicked (killer560, 2026-10-09: "I tried doing it on F1 with triggerbot and it
     * didn't work"). Aiming is a line-of-sight question; only an ATTACK is limited to melee reach (see {@link #tick}).
     */
    private static final double AIM_RANGE = 48.0;
    /** Trigger Bot left clicks sent, and how many of them hit an entity (the testkit reads these). */
    private static int triggerClicks;
    private static int triggerAttacks;
    private static String lastTrigger = "none";
    /** A gap this long with no move packet means the stand finished its trip and a later packet starts a new
     *  one - see {@link #onMoveEntity}. */
    private static final long RESETTLE_GAP_TICKS = 10L;
    /** Entity scan for the Watcher runs at 1 Hz, and only while one has not been found yet. */
    private static final int WATCHER_SCAN_INTERVAL_TICKS = 20;

    /** One sound of a kind per this many ticks: a wave settling over two or three ticks is one sound. */
    private static final long SOUND_MIN_GAP_TICKS = 4L;
    /** A kill moment more than this far in the past when first seen is not announced. */
    private static final double SOUND_STALE_TICKS = 10.0;
    private static long lastStartSoundTick = Long.MIN_VALUE / 2;
    private static long lastKillSoundTick = Long.MIN_VALUE / 2;
    private static int countdownStartSoundsPlayed;
    private static int killSoundsPlayed;
    /** Ticks left on the countdown when the last mob turned killable / the last Trigger Bot click went out, and the
     *  ping each was judged with (the testkit reads these to show both move by the measured round trip). */
    private static double lastKillableAt = Double.NaN;
    private static int lastKillablePingMs = -1;
    private static double lastTriggerAt = Double.NaN;

    private static Integer watcherEntityId;
    private static final Map<ArmorStand, BloodMobState> bloodMobs = new HashMap<>();
    private static Object lastLevel = null;
    private static int watcherScanCounter = 0;

    private BloodCampFeature() {
    }

    public static void register() {
        // The box, the kill sound and the Trigger Bot's lead all time themselves by the measured round trip.
        com.killer560.hub.util.PingTracker.register();
        com.killer560.hub.util.PingTracker.addDemand(() -> BloodCampConfig.getInstance().isEnabled());
        WorldRenderUtils.initThroughWalls();
        BloodCampMoveTimer.register();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("BloodCampFeature", client -> tick()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> endTick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BloodCampFeature::onWorldRender);
        // The Aura's turn moves every frame (a 20 Hz staircase is what made it look snappy); clicks stay on the tick.
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (isActive()) {
                AIM.frame(Minecraft.getInstance());
            }
        });
        for (com.killer560.hub.hud.HudElement element : hudElements()) {
            net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "bloodcamp_" + element.id()),
                    (graphics, deltaTracker) -> drawInGame(graphics, element));
        }
    }

    /** The lead registers these into {@code hud.HudElementRegistry} (one call each) so the HUD editor can move
     *  and scale them; the same instances are drawn in-game by {@link #drawInGame}. Same split
     *  {@code dungeonalerts.DungeonAlertsFeature} uses. */
    public static java.util.List<com.killer560.hub.hud.HudElement> hudElements() {
        return java.util.List.of(BloodCampMoveTimer.HUD);
    }

    private static void drawInGame(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   com.killer560.hub.hud.HudElement element) {
        Minecraft client = Minecraft.getInstance();
        // menuOpen(), not "screen != null": chat must not hide this (killer560), the HUD editor still does.
        if (client.player == null || com.killer560.hub.hud.HudVisibility.menuOpen() || McCompat.hudHidden(client)
                || !com.killer560.hub.util.SkyblockGate.allows() || !element.isEnabledInSettings()) {
            // Off: the kill popup draws only while relevant() (the same two settings), so skip drawAt's position and
            // pose work too (95-perf, 2026-10-15).
            return;
        }
        com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, element);
    }

    private static boolean isActive() {
        // Every floor's Blood Room, never the boss fight - NoammAddons' own gate. This used to be F7/M7 only,
        // which is why it worked in F7 and silently did nothing everywhere else. The tracking also runs for Dungeon
        // ESP's Blood Mobs (it highlights the mobs found here), even with Blood Camp itself off.
        // The field read first: outside a dungeon (most frames) no config is consulted (fps-auto-1015).
        return DungeonState.isInDungeon() && (BloodCampConfig.getInstance().isEnabled()
                || com.killer560.hub.mobesp.MobEspConfig.getInstance().isBloodMobsEnabled())
                && !com.killer560.hub.livemap.LiveMapFeature.isInBoss();
    }

    // ------------------------------------------------------------------
    // Real packet hooks - called from BloodCampPacketMixin
    // ------------------------------------------------------------------

    /** Detects the real Watcher (a real {@code Zombie} wearing one of the real watcher skull textures) -
     *  ported from Noamm's own real {@code ClientboundSetEquipmentPacket} check. */
    public static void onSetEquipment(ClientboundSetEquipmentPacket packet, Level level) {
        if (!isActive()) {
            return;
        }
        ItemStack head = null;
        for (var slot : packet.getSlots()) {
            if (slot.getFirst() == EquipmentSlot.HEAD) {
                head = slot.getSecond();
                break;
            }
        }
        if (head == null || head.isEmpty() || !head.is(Items.PLAYER_HEAD)) {
            return;
        }
        // killer560: "Whenever I join p3sim I get disconnected ... with the line Network Protocol Error. This
        // only happens if someone else is in my server though." Another player's head slot is a PLAYER_HEAD
        // with no resolvable "textures" property (p3sim serves unsigned skins), so getSkullTexture returns
        // null - and Set.of(...).contains(null) throws NPE, which inside a packet handler makes the client
        // disconnect itself with disconnect.packetError. Never hand a null to an immutable Set.
        String texture = getSkullTexture(head);
        if (watcherEntityId == null && texture != null && WATCHER_SKULL_TEXTURES.contains(texture)) {
            watcherEntityId = packet.getEntity();
        }
    }

    /** Real movement-based extrapolation, ported directly from Noamm's own real math - see this class's
     *  own doc comment. */
    public static void onMoveEntity(ClientboundMoveEntityPacket packet, Level level) {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        if (!isActive()) {
            return;
        }
        if (packet.getXa() == 0 && packet.getYa() == 0 && packet.getZa() == 0) {
            return;
        }
        if (!(packet.getEntity(level) instanceof ArmorStand entity)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || watcherEntityId == null) {
            return;
        }
        Entity watcher = level.getEntity(watcherEntityId);
        if (watcher == null || watcher.distanceToSqr(entity) > 400) {
            return;
        }
        ItemStack item = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (!item.is(Items.PLAYER_HEAD)) {
            return;
        }
        // Same null-into-Set.of hazard as onSetEquipment above - see the comment there.
        String mobTexture = getSkullTexture(item);
        if (mobTexture == null || !MOB_SKULL_TEXTURES.contains(mobTexture)) {
            return;
        }

        // NoammAddons' model, exactly (killer560, 2026-10-07: "nearly identical to noamm's in look and function").
        // Its sum is Short / 4096 in Kotlin - INTEGER division - so a blood mob's sub-block step adds nothing and the
        // sample is the stand's position BEFORE this packet moves it (this hook runs ahead of the vanilla handler).
        // The trip therefore starts at the skull's spot in the wall, not one step out of it; this used to add the
        // exact step (/ 4096.0), which put the start, and so the predicted landing spot, one step too far along.
        Vec3 packetVec = new Vec3(
                entity.getX() + packet.getXa() / 4096,
                entity.getY() + packet.getYa() / 4096,
                entity.getZ() + packet.getZa() / 4096
        );

        long nowTick = client.level != null ? client.level.getGameTime() : 0L;
        // firstSpawns is the Watcher's, not this mob's: his first wave settles 40 ticks slower and that state ends
        // on his "Let's see how you can handle this." line (Odin clears its own flag on exactly that line). This
        // used to clear after the FIRST tracked mob, so every other mob of the first wave counted down 2s early.
        boolean firstSpawn = BloodCampMoveTimer.isFirstSpawns();
        BloodMobState data = bloodMobs.get(entity);
        if (data == null) {
            data = new BloodMobState(packetVec, nowTick, firstSpawn);
            bloodMobs.put(entity, data);
        } else if (nowTick - data.lastMoveTick >= RESETTLE_GAP_TICKS) {
            // This class's own doc: "the SAME entity periodically repositions". Deltas arrive every tick while a
            // mob is travelling, so a gap usually means the last trip ended - without restarting here the start
            // vector, the countdown origin and the accumulated delta history all stayed on trip #1 forever.
            //
            // But a gap is ALSO what a server or network stall looks like from here (killer560, 2026-10-08: "Blood camp
            // can break and not show the path if the server lags for a tick"): no packets for half a second, then the
            // same trip carries on. Restarting then threw the path away (endVector null until two more packets), moved
            // the start into mid-air and restarted the countdown. So a gap only starts a new trip when the old one is
            // over - the stand reached its predicted spot, or the gap is longer than any stall - or when the stand
            // now goes a different way. Otherwise the trip, its path and its countdown are kept and simply resume.
            if (continuesTrip(data, packetVec, nowTick - data.lastMoveTick)) {
                stallsBridged++;
            } else {
                data.restart(packetVec, nowTick, firstSpawn);
                data.firstPacketExact = null;
            }
        }
        if (data.firstPacketExact == null) {
            // Where the trip's first packet actually put the stand - only for the testkit, which measures the old
            // start (this point) against the new one (startVec, the wall spot) on the same movement.
            data.firstPacketExact = new Vec3(entity.getX() + packet.getXa() / 4096.0,
                    entity.getY() + packet.getYa() / 4096.0, entity.getZ() + packet.getZa() / 4096.0);
        }
        data.lastMoveTick = nowTick;

        Vec3 delta = packetVec.subtract(data.lastPosition);
        data.lastPosition = packetVec;
        if (delta.lengthSqr() > 0) {
            data.deltaHistory.addLast(delta);
        }

        double spawnScale = data.firstSpawn ? 16.1 : 11.9;
        Vec3 total = Vec3.ZERO;
        for (Vec3 d : data.deltaHistory) {
            total = total.add(d);
        }
        // Only a real direction replaces the path; a sum that cancels to nothing (a step back after a correction
        // undoing the step before) keeps the last good one instead of dropping it.
        if (total.lengthSqr() > 1e-8) {
            data.endVector = data.startVec.add(total.normalize().scale(spawnScale));
        }
    }

    /** A move-packet gap longer than this is never a stall: the stand has been somewhere else for 5 s. */
    private static final long MAX_STALL_TICKS = 100L;
    /** Within this of its predicted spot the stand has arrived, so the next movement is a new trip. */
    private static final double ARRIVED_DISTANCE = 1.0;
    /** How many move-packet gaps were bridged as a stall instead of restarting the trip (the testkit reads it). */
    private static int stallsBridged;

    public static int stallsBridged() {
        return stallsBridged;
    }

    /**
     * Whether movement resuming after a gap of {@code gapTicks} is the SAME trip carrying on after a stall: the trip
     * has a path, the stand had not reached its predicted spot, the gap is shorter than {@link #MAX_STALL_TICKS},
     * and the stand is not now heading back the way it came.
     */
    static boolean continuesTrip(BloodMobState data, Vec3 packetVec, long gapTicks) {
        if (data.endVector == null || gapTicks > MAX_STALL_TICKS) {
            return false;
        }
        if (data.lastPosition.distanceTo(data.endVector) <= ARRIVED_DISTANCE) {
            return false;
        }
        Vec3 tripDir = data.endVector.subtract(data.startVec);
        Vec3 step = packetVec.subtract(data.lastPosition);
        return step.lengthSqr() < 1e-8 || tripDir.lengthSqr() < 1e-8 || step.dot(tripDir) >= 0.0;
    }

    public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, Level level) {
        if (watcherEntityId != null && packet.getEntityIds().contains(watcherEntityId)) {
            watcherEntityId = null;
        }
        if (!bloodMobs.isEmpty()) { // no iterator and capturing lambda per remove packet when nothing is tracked
            bloodMobs.keySet().removeIf(entity -> packet.getEntityIds().contains(entity.getId()));
        }
    }

    /** Real base64 "textures" property value off a real player-head ItemStack - same real
     *  {@code DataComponents.PROFILE -> partialProfile() -> properties -> "textures"} chain this
     *  codebase's own {@code SecretsFeature} already uses successfully for real skull identification,
     *  extended one step further to the texture string itself instead of stopping at the profile UUID
     *  (needed here since two of the real mob skulls have no attached player profile at all). */
    private static String getSkullTexture(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResolvableProfile profile = stack.get(net.minecraft.core.component.DataComponents.PROFILE);
        if (profile == null) {
            return null;
        }
        GameProfile partial = profile.partialProfile();
        if (partial == null) {
            return null;
        }
        var textures = partial.properties().get("textures");
        Iterator<Property> it = textures.iterator();
        return it.hasNext() ? it.next().value() : null;
    }

    // ------------------------------------------------------------------
    // Tick: countdown/trigger bot/aura
    // ------------------------------------------------------------------

    private static void tick() {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            TAG_MEMO.clear(); // entity ids belong to the level
            bloodMobs.clear();
            watcherEntityId = null;
            BloodCampMoveTimer.reset();
            AIM.reset(null);
            reportedYaw = Float.NaN;
            reportedPitch = Float.NaN;
            // a new level's game time can be behind the old one's; never let that hold a sound off
            lastStartSoundTick = Long.MIN_VALUE / 2;
            lastKillSoundTick = Long.MIN_VALUE / 2;
        }
        if (!isActive()) {
            if (!bloodMobs.isEmpty() || watcherEntityId != null) {
                bloodMobs.clear();
                watcherEntityId = null;
            }
            if (AIM.isTurning() || AIM.ownsCamera()) {
                AIM.reset(client.player);
            }
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        // The Watcher is normally caught from his equipment packet, but that packet only arrives once - if Blood
        // Camp was switched on mid-run, or the floor was not detected yet when it landed, nothing was ever tracked
        // and the whole feature silently did nothing. A 1 Hz scan closes that hole.
        if (watcherEntityId != null && client.level.getEntity(watcherEntityId) == null) {
            watcherEntityId = null;
        }
        if (watcherEntityId == null) {
            scanForWatcher(client);
        }

        long now = client.level.getGameTime();
        int pingMs = pingMs(client);
        tagBloodMobs(client, now);
        if (!cfg.isEnabled()) {
            return; // tracking for the Blood Mob ESP only
        }
        tickSounds(cfg, now, pingMs);
        confirmHits(client, now);

        // killer560, 2026-10-15: "Aura and triggerbot should both only work if I am in the blood room."
        boolean inRoom = inBloodRoom(client);
        lastInBloodRoom = inRoom;

        // ---- Aura: pick the mob, then turn (before the Trigger Bot looks, as a hand would already be moving) ------
        // Two kinds of target. A mob still on its way (a tracked trip, from AURA_START_TICKS before it is due), and -
        // killer560, 2026-10-15: "if I run into the room after the kill timer and there are mobs on the floor the aura
        // should kill them" - any mob already standing in the Blood Room that no live trip accounts for: landed before
        // he arrived, past its trip, or hit but still alive. Only ever one he can SEE (killer560, 2026-10-15: "make
        // sure it doesn't track them through walls"): a ray from his eye reaches its box without meeting a block.
        Map.Entry<ArmorStand, BloodMobState> auraTrip = null;
        Entity auraFloor = null;
        if (cfg.isAuraEnabled() && inRoom) {
            double best = Double.MAX_VALUE;
            for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
                BloodMobState data = entry.getValue();
                if (!liveTrip(entry.getKey(), data, now)) {
                    continue;
                }
                double remaining = remainingTicks(data, now);
                if (remaining > AURA_START_TICKS) {
                    continue;
                }
                Entity mob = nearestMob(client, data.endVector);
                if (!visible(client, mob != null ? mob.getBoundingBox() : aimBox(data.endVector))) {
                    continue;
                }
                if (remaining < best) {
                    best = remaining;
                    auraTrip = entry;
                }
            }
            // A mob already on the floor is killable now: it goes ahead of a trip still more than its own angle away.
            float yaw = client.player.getYRot();
            float pitch = client.player.getXRot();
            for (Entity mob : floorMobs(client, now)) {
                if (!visible(client, mob.getBoundingBox())) {
                    continue;
                }
                float[] a = BloodCampAim.angles(client.player.getEyePosition(), mob.getBoundingBox().getCenter());
                double score = (Math.abs(net.minecraft.util.Mth.wrapDegrees(a[0] - yaw)) + Math.abs(a[1] - pitch)) * 0.01;
                if (score < best) {
                    best = score;
                    auraFloor = mob;
                    auraTrip = null;
                }
            }
        }
        Vec3 auraPoint = auraFloor != null ? aimPoint(auraFloor, auraFloor.getId())
                : auraTrip == null ? null : aimPoint(client, auraTrip.getValue());
        Object auraKey = auraFloor != null ? (Object) ("mob" + auraFloor.getId())
                : auraTrip == null ? null : auraTrip.getKey().getId();
        lastAuraPoint = auraPoint;
        lastAuraFloorTarget = auraFloor == null ? -1 : auraFloor.getId();
        AIM.tick(client, auraKey, auraPoint, cfg.isAuraEnabled() && cfg.isAuraFreecam());

        // ---- Attack speed (killer560, 2026-10-15: "limit it to only be able to attack as fast as their attack speed
        // allows them to. For triggerbot do the same."): no click from Blood Camp - Trigger Bot, its retries or the Aura -
        // comes sooner after the last than the held weapon's cooldown (util/AttackCooldown).
        int cooldown = com.killer560.hub.util.AttackCooldown.intervalTicks(client);
        lastCooldownTicks = cooldown;
        boolean cooldownReady = now - lastClickTick >= cooldown;
        boolean neverMiss = cfg.isNeverMiss();

        // ---- Trigger Bot ------------------------------------------------------------------------------------------
        // killer560 (2026-09-20): "if I have two levers in my range at once ... have it only pick one and then the
        // other on the next tick". Pick the most-due mob here and send at most one click below.
        //
        // WHEN (killer560, 2026-10-15: "Triggerbot was attacking them before the kill timer actually went off", and an
        // offset of -10..10, default -1, negative = late). The kill timer is the moment the box turns green and the Kill
        // Sound plays: the first tick the time left is under your ping (killable). The click goes on the first tick with
        // time left < ping ticks + offset: the kill timer's own tick, shifted by exactly the offset. With Auto Detect Lag
        // off the ping term is left out, so the click is timed from the countdown's own end instead.
        //
        // NEVER MISS (killer560, 2026-10-15: "attacks the tick it detects the mob is actually there not just when it
        // thinks it will spawn"): the timer is not used at all - the first click goes the first tick a living mob stands
        // on the landing spot, in reach under his look, in sight.
        double clickBelow = triggerTargetTicks(cfg, client);
        boolean triggerBotUsable = cfg.isTriggerBotEnabled() && inRoom
                && !com.killer560.hub.util.ActionGate.containerScreenOpen(client);
        double reach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
        Entity triggerTarget = null;
        Entity triggerHit = null;
        BloodMobState triggerData = null;
        boolean triggerIsRetry = false;
        double bestRemaining = Double.MAX_VALUE;
        if (triggerBotUsable && cooldownReady) {
            int retryWindow = hitConfirmTicks(pingMs);
            for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
                ArmorStand entity = entry.getKey();
                BloodMobState data = entry.getValue();
                if (data.endVector == null || entity.isRemoved() || data.confirmed || data.gaveUp) {
                    continue;
                }
                double remainingTicks = remainingTicks(data, now);
                if (remainingTicks >= bestRemaining) {
                    continue;
                }
                boolean retry = false;
                Entity mob = nearestMob(client, data.endVector);
                if (data.clicks == 0) {
                    if (neverMiss) {
                        // Really there: a living mob on the spot, his look on it and reaching it before any block - at any
                        // range (a mage beam or a shortbow does not need melee reach).
                        if (mob == null || remainingTicks < -RETRY_STALE_TICKS || !aimedAt(client, mob.getBoundingBox())) {
                            continue;
                        }
                    } else if (remainingTicks >= clickBelow || remainingTicks < clickBelow - TRIGGER_WINDOW_TICKS) {
                        // Only inside the window: due, but not so long ago that the prediction is stale.
                        continue;
                    }
                } else {
                    // killer560, 2026-10-15: "If you detect that it missed hitting a mob then it should try to kill them
                    // again immediately." A miss: a real mob stands on the landing spot, it took no damage within one
                    // round trip + HIT_CONFIRM_TICKS of the click and is still alive (confirmHits) - or the click was a
                    // swing on air and a mob is now under the crosshair in reach (it was not there yet). With no mob
                    // there at all there is nothing to have missed, so a swing at an empty spot stays one click.
                    if (data.clicks > MAX_RETRIES) {
                        if (now - data.lastClickTick > retryWindow) {
                            data.gaveUp = true;
                        }
                        continue;
                    }
                    if (mob == null || remainingTicks < -RETRY_STALE_TICKS || now - data.lastClickTick < data.retryGap) {
                        continue;
                    }
                    boolean windowOver = now - data.lastClickTick > retryWindow;
                    boolean nowReachable = !data.lastClickAttacked
                            && resolveAttackTarget(client, entity, data.endVector, reach) != null;
                    if (!windowOver && !nowReachable) {
                        continue;
                    }
                    retry = true;
                }
                // "if my crosshair is looking in a blood mobs spawn hitbox when it goes to spawn have it click once."
                // aimBox is that spawn hitbox, a mob-sized box standing on the predicted spot and reaching up through
                // the drawn box; the stand's or the real mob's own box is accepted too. Judged on the BODY's look (what
                // the movement packets report - the camera can be elsewhere in Freecam Mode), on both the look the
                // server was last told and the one this tick's packet will carry, and never through a block.
                if (!aimedAt(client, aimBox(data.endVector)) && !aimedAt(client, entity.getBoundingBox())
                        && (mob == null || !aimedAt(client, mob.getBoundingBox()))) {
                    continue;
                }
                triggerTarget = entity;
                triggerHit = resolveAttackTarget(client, entity, data.endVector, reach);
                triggerData = data;
                triggerIsRetry = retry;
                bestRemaining = remainingTicks;
            }
        }

        // ---- Aura's own attack -------------------------------------------------------------------------------------
        // The Aura kills what it aims at: a floor mob at once, a mob on its way once it is killable (the Trigger Bot's
        // moment, or with Never Miss the tick it is really there), at the weapon's pace, until it dies. At ANY range
        // (killer560, 2026-10-15: "It should always attack with whatever weapon I am holding even if they are out of
        // range no matter the weapon if I am in the room and they are not through a wall. Because mage beam can kill
        // them or short bows. No one really melee them.") - so long as his body's look is on the mob and reaches it
        // before any block. What is sent is what his own click with that weapon sends (see click()).
        Entity auraMob = null;
        BloodMobState auraHitData = null;
        if (triggerTarget == null && cfg.isAuraEnabled() && inRoom
                && !com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            Entity mob = null;
            if (auraFloor != null) {
                mob = auraFloor;
            } else if (auraTrip != null) {
                BloodMobState data = auraTrip.getValue();
                double remaining = remainingTicks(data, now);
                Entity m = nearestMob(client, data.endVector);
                if (m != null && (neverMiss || remaining < clickBelow)) {
                    mob = m;
                    auraHitData = data;
                }
            }
            if (mob != null && aimedAt(client, mob.getBoundingBox())) {
                auraMob = mob;
            } else {
                auraHitData = null;
            }
        }

        // ---- Human clicks between the hits (killer560, 2026-10-15: "make sure it is not only when the server should
        // register, there should be some other ones thrown in to make it look more human as well"). While the Aura is
        // on a mob, or the Trigger Bot is still on a mob it clicked that is not hit yet, extra clicks go between the
        // registering ones at a spam-clicker's irregular pace (HumanClicks). They only fall well inside the weapon's
        // cooldown - at least 2 ticks before the next registering hit may go, so none of them can land as a hit, and
        // the registering hit still goes on the first tick the cap allows. Hypixel only: there a hit inside the
        // cooldown does nothing; on a vanilla server it would cut the next hit's damage.
        Entity fillerMob = null;
        if (auraMob == null || !cooldownReady) {
            fillerMob = auraMob;
            auraMob = cooldownReady ? auraMob : null;
            if (fillerMob == null && triggerBotUsable) {
                for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
                    BloodMobState data = entry.getValue();
                    if (data.clicks == 0 || !liveTrip(entry.getKey(), data, now)) {
                        continue;
                    }
                    Entity m = nearestMob(client, data.endVector);
                    if (m != null && aimedAt(client, m.getBoundingBox())) {
                        fillerMob = m;
                        break;
                    }
                }
            }
        }
        if (auraMob != null) {
            fillerMob = null;
        }
        boolean fillerNow = false;
        if (fillerMob == null) {
            HUMAN.idle(now);
        } else if (triggerTarget == null && humanClicks && com.killer560.hub.util.AttackCooldown.onHypixel(client)
                && now - lastClickTick <= cooldown - 2 && HUMAN.due(now)
                && !com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            fillerNow = true;
        }

        // Mod-wide one-interaction-per-tick gate, after the target is chosen and before anything is marked: a
        // denial leaves the mob as it was, so it is simply clicked on the next tick it allows.
        // killer560, 2026-10-15: "range only blood mobs, because if you do any mob, you could end up hitting mobs
        // outside of the room" - a click (beam, arrow or hit) only goes when nothing but the target is in its way.
        Entity firing = triggerTarget != null ? (triggerHit != null ? triggerHit : nearestMob(client, triggerData.endVector))
                : auraMob != null ? auraMob : fillerMob;
        if ((fillerNow || triggerTarget != null || auraMob != null) && !lineOfFireClear(client, firing)) {
            lineOfFireBlocks++;
            return;
        }
        if (fillerNow && com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.BLOOD_CAMP)) {
            Entity melee = lookTarget(client, reach);
            Click kind = click(client, melee == fillerMob ? fillerMob : null);
            HUMAN.clicked(now);
            fillerClicks++;
            ALL_CLICKS.addLast(now);
            while (ALL_CLICKS.size() > 64) {
                ALL_CLICKS.removeFirst();
            }
            lastTrigger = "human " + kind.name().toLowerCase(Locale.ROOT) + " between hits";
            return;
        }
        if ((triggerTarget != null || auraMob != null)
                && com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.BLOOD_CAMP)) {
            lastClickTick = now;
            HUMAN.clicked(now);
            ALL_CLICKS.addLast(now);
            while (ALL_CLICKS.size() > 64) {
                ALL_CLICKS.removeFirst();
            }
            RECENT_CLICKS.addLast(now);
            while (RECENT_CLICKS.size() > 32) {
                RECENT_CLICKS.removeFirst();
            }
            if (triggerTarget == null) {
                Entity melee = lookTarget(client, reach);
                Click done = click(client, melee == auraMob ? auraMob : null);
                auraAttacks++;
                if (auraHitData != null) {
                    auraHitData.clicks++;
                    auraHitData.lastClickTick = now;
                    auraHitData.lastClickAttacked = done == Click.ATTACK;
                    auraHitData.lastAttackedId = done == Click.ATTACK ? auraMob.getId() : -1;
                    auraHitData.retryGap = 2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
                }
                lastTrigger = "aura " + done.name().toLowerCase(Locale.ROOT) + " " + auraMob.getType().toShortString()
                        + " #" + auraMob.getId() + String.format(Locale.US, ", %.1f blocks",
                        client.player.getEyePosition().distanceTo(auraMob.getBoundingBox().getCenter()));
                LOGGER.info("[BloodCamp] Aura clicked: {}", lastTrigger);
                return;
            }
            Click done = click(client, triggerHit);
            if (done == Click.ATTACK) {
                triggerAttacks++;
            }
            triggerData.triggerBotClicked = true;
            triggerData.clicks++;
            triggerData.lastClickTick = now;
            triggerData.lastClickAttacked = done == Click.ATTACK;
            triggerData.lastAttackedId = done == Click.ATTACK ? triggerHit.getId() : -1;
            triggerData.retryGap = 2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
            triggerClicks++;
            if (triggerIsRetry) {
                triggerRetries++;
            } else {
                lastTriggerAt = bestRemaining;
                lastTriggerMobPresent = nearestMob(client, triggerData.endVector) != null;
            }
            lastTrigger = (triggerIsRetry ? "retry " : "") + (done == Click.ATTACK ? "attack "
                    + triggerHit.getType().toShortString() + " #" + triggerHit.getId() : done.name().toLowerCase(Locale.ROOT))
                    + String.format(Locale.US, " at %.1f ticks, %.1f blocks", bestRemaining,
                    client.player.getEyePosition().distanceTo(triggerData.endVector));
            LOGGER.info("[BloodCamp] Trigger Bot clicked: {}", lastTrigger);
        }
    }

    /** The between-hits click timing (a spam-clicker's pace; see {@link HumanClicks}). */
    private static final HumanClicks HUMAN = new HumanClicks();
    /** Testkit hook: the cases that measure only the registering hits switch the between-hits clicks off. Not a saved
     *  setting - it is always on in play. */
    static boolean humanClicks = true;
    private static int fillerClicks;
    /** Every Blood Camp click's game tick, registering and between-hits alike (last 64, oldest first). */
    private static final java.util.ArrayDeque<Long> ALL_CLICKS = new java.util.ArrayDeque<>();

    public static int fillerClicks() {
        return fillerClicks;
    }

    public static List<Long> allClickTicks() {
        return new ArrayList<>(ALL_CLICKS);
    }

    public static void setHumanClicks(boolean on) {
        humanClicks = on;
    }

    /** What one Blood Camp click sent. */
    enum Click { ATTACK, SWING, USE }

    /** Testkit: clicks of each kind sent (attack, swing on air, right-click use). */
    private static final int[] CLICKS_BY_KIND = new int[3];

    public static int clicksOfKind(String kind) {
        return CLICKS_BY_KIND[Click.valueOf(kind).ordinal()];
    }

    /**
     * One click with whatever he holds, the way his own button would send it. A Hypixel shortbow ("Shortbow: Instantly
     * shoots!" in its lore) shoots on a right click: a use, which vanilla lets go of on the next tick because the use key
     * is not held - a tap. Anything else is a left click: an attack plus the swing on a mob within melee reach under his
     * look ({@code melee}), otherwise the swing alone - which is all a real left click sends there, and what a mage
     * weapon's beam (and Hypixel's other left-click abilities) fire on. The attack-speed cap is the caller's.
     */
    private static Click click(Minecraft client, Entity melee) {
        if (com.killer560.hub.util.AttackCooldown.isShortbow(client.player.getMainHandItem())) {
            client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            CLICKS_BY_KIND[Click.USE.ordinal()]++;
            return Click.USE;
        }
        if (melee != null) {
            client.gameMode.attack(client.player, melee);
        }
        client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        Click kind = melee != null ? Click.ATTACK : Click.SWING;
        CLICKS_BY_KIND[kind.ordinal()]++;
        return kind;
    }

    // ---- Blood mobs, by identity ---------------------------------------------------------------------------------

    /**
     * Entity ids of the Watcher's blood mobs: the mob that stands on a tracked trip's landing spot as the trip ends.
     * Kept for as long as the entity is alive, wherever it walks (killer560, 2026-10-15: "they walk out of the room"),
     * cleared with the level. The Aura, the Trigger Bot and Dungeon ESP's Blood Mobs only ever act on these
     * (killer560: "don't have it attack any mob, range only blood mobs").
     */
    private static final Set<Integer> BLOOD_IDS = new java.util.HashSet<>();
    /** A trip's mob is taken when it is due within this many ticks (or later): the moment it lands. */
    private static final double TAG_WITHIN_TICKS = 10.0;
    private static int lineOfFireBlocks;

    public static Set<Integer> bloodMobIds() {
        return Set.copyOf(BLOOD_IDS);
    }

    public static int lineOfFireBlocks() {
        return lineOfFireBlocks;
    }

    static boolean isBloodMob(Entity e) {
        return isAttackableMob(e) && BLOOD_IDS.contains(e.getId());
    }

    /**
     * {@code BloodMobNames.isBloodTag(strip(stand.getName().getString()).trim())}, remembered per stand for as long as
     * its name Component is the same object (a name changes only by a new one arriving in the entity's data). The scan
     * below reads every named stand of the loaded world every 5 ticks, and the sim's and a real floor's mob and secret
     * labels made that a Component flatten, a strip and a regex each, every time (fps-auto-1015).
     */
    private static boolean isBloodTagCached(ArmorStand stand) {
        net.minecraft.network.chat.Component name = stand.getName();
        TagMemo m = TAG_MEMO.get(stand.getId());
        if (m != null && m.name == name) {
            return m.tag;
        }
        String plain = com.killer560.hub.util.ChatObserver.stripCodes(name.getString()).trim();
        boolean tag = BloodMobNames.isBloodTag(plain);
        if (TAG_MEMO.size() > 4096) {
            TAG_MEMO.clear(); // entity ids of a long session; the memo only saves work, losing it costs one re-read
        }
        TAG_MEMO.put(stand.getId(), new TagMemo(name, tag));
        return tag;
    }

    private record TagMemo(net.minecraft.network.chat.Component name, boolean tag) {
    }

    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<TagMemo> TAG_MEMO =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

    private static void tagBloodMobs(Minecraft client, long now) {
        BLOOD_IDS.removeIf(id -> {
            Entity e = client.level.getEntity(id);
            return e == null || e.isRemoved() || !e.isAlive();
        });
        // By name, every 5 ticks (killer560, 2026-10-15): a blood mob that landed while he was too far away for the
        // client to see its trip still has its name tag - see BloodMobNames.
        if (now % 5 == 0) {
            for (Entity e : client.level.entitiesForRendering()) {
                if (e instanceof ArmorStand stand && stand.hasCustomName()) {
                    if (isBloodTagCached(stand)) {
                        Entity mob = BloodMobNames.mobFor(client, stand);
                        if (mob != null && (watcherEntityId == null || mob.getId() != watcherEntityId)) {
                            BLOOD_IDS.add(mob.getId());
                        }
                    }
                } else if (BloodMobNames.isNamedNpc(client, e)) {
                    BLOOD_IDS.add(e.getId());
                }
            }
        }
        for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
            BloodMobState data = entry.getValue();
            if (data.endVector == null || remainingTicks(data, now) > TAG_WITHIN_TICKS
                    || remainingTicks(data, now) <= -RETRY_STALE_TICKS) {
                continue;
            }
            if (data.mobId >= 0 && BLOOD_IDS.contains(data.mobId)) {
                continue;
            }
            AABB search = new AABB(data.endVector, data.endVector).inflate(TARGET_SEARCH_RADIUS);
            Entity best = null;
            double bestD = Double.MAX_VALUE;
            for (Entity c : client.level.getEntities(client.player, search, BloodCampFeature::isAttackableMob)) {
                if (watcherEntityId != null && c.getId() == watcherEntityId) {
                    continue;
                }
                double d = c.position().distanceToSqr(data.endVector);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            if (best != null) {
                data.mobId = best.getId();
                BLOOD_IDS.add(best.getId());
            }
        }
    }

    /**
     * Whether a click aimed along his body's look would only meet {@code target}: no other living thing that is not a
     * blood mob inside the Blood Room within 0.6 blocks of the line, from his eye out to the first block (or 48 blocks)
     * - past the target too, because a beam or an arrow carries on. Real players are not in the way (nothing of his
     * hits them). The Watcher is in the way.
     */
    static boolean lineOfFireClear(Minecraft client, Entity target) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(client.player.getXRot(), client.player.getYRot());
        double len = blockDistance(client, eye, dir, AIM_RANGE);
        Vec3 end = eye.add(dir.scale(len));
        for (Entity e : client.level.getEntities(client.player, new AABB(eye, end).inflate(1.0),
                x -> x instanceof LivingEntity && !(x instanceof ArmorStand) && x.isAlive()
                        && !(x instanceof Player && x.getUUID().version() == 4))) {
            if (e == target) {
                continue;
            }
            if (!e.getBoundingBox().inflate(0.6).clip(eye, end).isPresent() && !e.getBoundingBox().inflate(0.6).contains(eye)) {
                continue;
            }
            if (!isBloodMob(e) || !inRoomTile(client, e.position())) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code p} is in the Watcher's room tile (the Blood Room). */
    private static boolean inRoomTile(Minecraft client, Vec3 p) {
        Entity watcher = watcherEntityId == null ? null : client.level.getEntity(watcherEntityId);
        return watcher != null && roomTile(p.x) == roomTile(watcher.getX()) && roomTile(p.z) == roomTile(watcher.getZ())
                && Math.abs(p.y - watcher.getY()) < 40.0;
    }

    /** Game tick of Blood Camp's last click (Trigger Bot or Aura) - the attack-speed cap counts from it. */
    private static long lastClickTick = Long.MIN_VALUE / 2;
    /** Testkit: the cap in force last tick, Aura attacks sent, the floor mob the Aura last aimed at (-1 none), and
     *  whether a living mob stood on the spot when the last first click went. */
    private static int lastCooldownTicks;
    private static int auraAttacks;
    private static int lastAuraFloorTarget = -1;
    private static boolean lastTriggerMobPresent;

    /** Game ticks of Blood Camp's last 32 clicks, oldest first (the testkit checks the client's own spacing). */
    private static final java.util.ArrayDeque<Long> RECENT_CLICKS = new java.util.ArrayDeque<>();

    public static List<Long> recentClickTicks() {
        return new ArrayList<>(RECENT_CLICKS);
    }

    public static int lastCooldownTicks() {
        return lastCooldownTicks;
    }

    public static int auraAttacks() {
        return auraAttacks;
    }

    public static int lastAuraFloorTarget() {
        return lastAuraFloorTarget;
    }

    public static boolean lastTriggerMobPresent() {
        return lastTriggerMobPresent;
    }

    /** A trip the Aura and the Trigger Bot still act on: it has a path, its stand is there, and it is not settled. */
    private static boolean liveTrip(ArmorStand stand, BloodMobState data, long now) {
        return data.endVector != null && !stand.isRemoved() && !data.confirmed && !data.gaveUp
                && remainingTicks(data, now) > -RETRY_STALE_TICKS;
    }

    /**
     * Mobs already standing in the Blood Room (the Watcher's room tile) that no live trip accounts for, the Watcher
     * himself excluded. A mob hit by a trip's click but still alive is one of these, so it is finished off.
     */
    private static List<Entity> floorMobs(Minecraft client, long now) {
        List<Entity> out = new ArrayList<>();
        Entity watcher = watcherEntityId == null ? null : client.level.getEntity(watcherEntityId);
        if (watcher == null) {
            return out;
        }
        double cx = com.killer560.hub.livemap.LiveMapFeature.START_X + 32.0 * roomTile(watcher.getX());
        double cz = com.killer560.hub.livemap.LiveMapFeature.START_Z + 32.0 * roomTile(watcher.getZ());
        AABB room = new AABB(cx - 16, watcher.getY() - 40, cz - 16, cx + 16, watcher.getY() + 40, cz + 16);
        for (Entity mob : client.level.getEntities(client.player, room, BloodCampFeature::isBloodMob)) {
            if (mob.getId() == watcherEntityId) {
                continue;
            }
            boolean onTrip = false;
            for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
                BloodMobState data = entry.getValue();
                if (liveTrip(entry.getKey(), data, now)
                        && mob.position().distanceTo(data.endVector) <= TARGET_SEARCH_RADIUS) {
                    onTrip = true;
                    break;
                }
            }
            if (!onTrip) {
                out.add(mob);
            }
        }
        return out;
    }

    /** The aim point on a floor mob: its upper chest, with a small fixed offset from its id (no two alike). */
    static Vec3 aimPoint(Entity mob, int seed) {
        AABB b = mob.getBoundingBox();
        java.util.Random r = new java.util.Random(seed * 31L + 7L);
        double w = b.getXsize();
        return new Vec3(b.getCenter().x + (r.nextDouble() * 2 - 1) * 0.12 * w,
                b.minY + b.getYsize() * (AIM_HEIGHT_FRACTION + (r.nextDouble() * 2 - 1) * 0.06),
                b.getCenter().z + (r.nextDouble() * 2 - 1) * 0.12 * w);
    }

    /**
     * Whether he can see any of {@code box}: a ray from his eye to its centre, its upper part or its top meets no block
     * first (killer560, 2026-10-15: "make sure it doesn't track them through walls").
     */
    static boolean visible(Minecraft client, AABB box) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 c = box.getCenter();
        Vec3[] points = {c, new Vec3(c.x, box.minY + box.getYsize() * 0.75, c.z), new Vec3(c.x, box.maxY - 0.05, c.z)};
        for (Vec3 p : points) {
            if (clearTo(client, eye, p)) {
                return true;
            }
        }
        return false;
    }

    /** No block between {@code from} and {@code to}. */
    private static boolean clearTo(Minecraft client, Vec3 from, Vec3 to) {
        net.minecraft.world.phys.HitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(from) >= from.distanceToSqr(to) - 1e-6;
    }

    /** How far his body's look runs before it meets a block (at most {@code range}). */
    private static double blockDistance(Minecraft client, Vec3 eye, Vec3 dir, double range) {
        Vec3 end = eye.add(dir.scale(range));
        net.minecraft.world.phys.HitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? range : hit.getLocation().distanceTo(eye);
    }

    /** The nearest living, attackable mob his body's look meets within {@code range}, before any block, or null. */
    private static Entity lookTarget(Minecraft client, double range) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(client.player.getXRot(), client.player.getYRot());
        double limit = Math.min(range, blockDistance(client, eye, dir, range));
        Vec3 end = eye.add(dir.scale(limit));
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(client.player,
                new AABB(eye, end).inflate(1.0), BloodCampFeature::isAttackableMob)) {
            Vec3 hit = candidate.getBoundingBox().clip(eye, end).orElse(null);
            if (hit == null) {
                continue;
            }
            double d = eye.distanceToSqr(hit);
            if (d < bestD) {
                bestD = d;
                best = candidate;
            }
        }
        return best;
    }

    /** Ticks before a mob is due the Aura starts turning toward it (a trip is 38.8 ticks, the first wave 78.8). */
    private static final double AURA_START_TICKS = 35.0;
    /** A click that did no damage is retried at most this many times per trip. */
    static final int MAX_RETRIES = 4;
    /** Retries (and the Aura) give a mob up this many ticks past its due moment. */
    private static final double RETRY_STALE_TICKS = 40.0;
    /** On top of the round trip, ticks a hit is given to show up (the hurt flash or the death) before it is a miss. */
    private static final int HIT_CONFIRM_TICKS = 4;

    static int hitConfirmTicks(int pingMs) {
        return (int) Math.ceil(pingMs / 50.0) + HIT_CONFIRM_TICKS;
    }

    /** The one Aura turn controller (per-frame smooth, his mouse wins - see {@link BloodCampAim}). */
    private static final BloodCampAim AIM = new BloodCampAim();
    /** The body's rotation the last movement packet reported (read at END_CLIENT_TICK, after it was sent). */
    private static float reportedYaw = Float.NaN;
    private static float reportedPitch = Float.NaN;
    /** Testkit: retries sent, the last aura aim point, and the room gate's last answer. */
    private static int triggerRetries;
    private static Vec3 lastAuraPoint;
    private static boolean lastInBloodRoom;

    public static int triggerRetries() {
        return triggerRetries;
    }

    public static Vec3 lastAuraPoint() {
        return lastAuraPoint;
    }

    public static boolean lastInBloodRoom() {
        return lastInBloodRoom;
    }

    public static int auraTakeovers() {
        return AIM.takeovers;
    }

    public static float auraMaxTickYaw() {
        return AIM.maxTickYaw;
    }

    public static float auraMaxTickPitch() {
        return AIM.maxTickPitch;
    }

    public static void resetAuraStats() {
        AIM.maxTickYaw = 0f;
        AIM.maxTickPitch = 0f;
        AIM.lastReactionTicks = -1;
    }

    public static int auraLastReactionTicks() {
        return AIM.lastReactionTicks;
    }

    public static boolean auraOwnsCamera() {
        return AIM.ownsCamera();
    }

    public static boolean auraTurning() {
        return AIM.isTurning();
    }

    /**
     * Whether he is inside the Blood Room. The map is asked first: when it knows the type of the room he stands in, that
     * decides. Otherwise the Watcher does - he never leaves the Blood Room, which is always a 1x1 room, so it is the
     * dungeon room tile (32 x 32 on the grid {@code LiveMapFeature} uses, centres at -185 + 32n) the Watcher is in.
     * Without a Watcher there are no blood mobs, so nothing is lost by answering "no".
     */
    static boolean inBloodRoom(Minecraft client) {
        if (client.player == null || client.level == null || watcherEntityId == null) {
            return false;
        }
        String type = com.killer560.hub.livemap.LiveMapFeature.currentRoomType();
        if (type != null) {
            return "BLOOD".equals(type);
        }
        Entity watcher = client.level.getEntity(watcherEntityId);
        if (watcher == null) {
            return false;
        }
        Vec3 p = client.player.position();
        Vec3 w = watcher.position();
        return roomTile(p.x) == roomTile(w.x) && roomTile(p.z) == roomTile(w.z) && Math.abs(p.y - w.y) < 40.0;
    }

    /** The room tile index along one axis (rounded to the nearest room centre, -185 + 32n). */
    static long roomTile(double coord) {
        return Math.round((coord - com.killer560.hub.livemap.LiveMapFeature.START_X) / 32.0);
    }

    /**
     * Marks a trip done once its mob is hurt or dead - the Aura moves on and no retry is sent. Hurt is the client's own
     * hurt flash ({@code hurtTime}, set by the server's damage event), so a teammate's hit counts too.
     */
    private static void confirmHits(Minecraft client, long now) {
        for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
            BloodMobState data = entry.getValue();
            if (data.endVector == null || data.confirmed) {
                continue;
            }
            if (data.lastAttackedId >= 0) {
                Entity attacked = client.level.getEntity(data.lastAttackedId);
                if (attacked == null || attacked.isRemoved() || !attacked.isAlive()) {
                    data.confirmed = true;
                    continue;
                }
            }
            if (remainingTicks(data, now) > 2.0) {
                continue; // nothing to hit yet
            }
            AABB search = new AABB(data.endVector, data.endVector).inflate(TARGET_SEARCH_RADIUS);
            for (Entity candidate : client.level.getEntities(client.player, search,
                    e -> e instanceof LivingEntity && !(e instanceof ArmorStand)
                            && !(e instanceof Player && e.getUUID().version() == 4))) {
                LivingEntity living = (LivingEntity) candidate;
                if (living.hurtTime > 0 || !living.isAlive()) {
                    data.confirmed = true;
                    break;
                }
            }
        }
    }

    /** The real mob standing on a landing spot (nearest to it), or null when only the stand is there. */
    private static Entity nearestMob(Minecraft client, Vec3 end) {
        AABB search = new AABB(end, end).inflate(TARGET_SEARCH_RADIUS);
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(client.player, search, BloodCampFeature::isBloodMob)) {
            double d = candidate.position().distanceToSqr(end);
            if (d < bestD) {
                bestD = d;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Where the Aura aims (killer560, 2026-10-15: "Aura is aiming very low it should aim higher"). It used to aim at the
     * predicted landing spot itself - the stand's FEET. Now: the real mob's upper chest ({@link #AIM_HEIGHT_FRACTION} of
     * its height) once it stands there, else {@link #AIM_HEIGHT_EMPTY} above the landing spot, the bottom of the box
     * Blood Camp draws. A small offset rolled per trip keeps two aims from being pixel-identical.
     */
    static Vec3 aimPoint(Minecraft client, BloodMobState data) {
        Entity mob = nearestMob(client, data.endVector);
        if (mob != null) {
            AABB b = mob.getBoundingBox();
            double w = b.getXsize();
            double h = b.getYsize();
            return new Vec3(b.getCenter().x + data.aimJitterX * w, b.minY + h * (AIM_HEIGHT_FRACTION + data.aimJitterY),
                    b.getCenter().z + data.aimJitterZ * w);
        }
        Vec3 end = data.endVector;
        return new Vec3(end.x + data.aimJitterX, end.y + AIM_HEIGHT_EMPTY + data.aimJitterY * 2, end.z + data.aimJitterZ);
    }

    /** Fraction of a mob's height the Aura aims at (upper chest), and the height above an empty landing spot. */
    static final double AIM_HEIGHT_FRACTION = 0.72;
    static final double AIM_HEIGHT_EMPTY = 1.5;

    /** END_CLIENT_TICK: the movement packet has gone; its rotation is what the server judges the next attack by. */
    private static void endTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            reportedYaw = Float.NaN;
            reportedPitch = Float.NaN;
            return;
        }
        reportedYaw = client.player.getYRot();
        reportedPitch = client.player.getXRot();
    }

    /**
     * Countdown sounds (killer560, 2026-10-07: "make the kill timer for blood mobs also make a sound once it starts
     * counting down and as you need to kill"). Legit, both builds. A mob's countdown STARTS the first tick its landing
     * box exists ({@code endVector} set by its first move packet), and it is KILLABLE the tick {@link #killable} says
     * so - your ping exceeds the time left, the same instant the box turns green. Each mob sounds each event once per trip; every mob that
     * reaches an event on the same tick shares ONE sound, and a sound of the same kind is held off for
     * {@link #SOUND_MIN_GAP_TICKS} after the last one, so a wave settling over a couple of ticks is one sound, not a
     * burst. A kill moment that is already stale (Blood Camp switched on mid-countdown) is marked without a sound.
     */
    private static void tickSounds(BloodCampConfig cfg, long nowTick, int pingMs) {
        boolean start = false;
        boolean kill = false;
        for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
            BloodMobState data = entry.getValue();
            if (data.endVector == null || entry.getKey().isRemoved()) {
                continue;
            }
            double remaining = remainingTicks(data, nowTick);
            if (!data.startSoundDone) {
                data.startSoundDone = true;
                start |= remaining > 0;
            }
            if (!data.killSoundDone && killable(remaining, pingMs)) {
                data.killSoundDone = true;
                lastKillableAt = remaining;
                lastKillablePingMs = pingMs;
                kill |= remaining > -SOUND_STALE_TICKS;
            }
        }
        float volume = cfg.getSoundVolume();
        if (kill && cfg.isKillSound() && volume > 0f && nowTick - lastKillSoundTick >= SOUND_MIN_GAP_TICKS) {
            lastKillSoundTick = nowTick;
            killSoundsPlayed++;
            com.killer560.hub.dungeonalerts.DungeonAlertsFeature.playSound(
                    com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(cfg.getKillSoundId()).sound(), volume, 1.0f);
        }
        if (start && cfg.isCountdownStartSound() && volume > 0f
                && nowTick - lastStartSoundTick >= SOUND_MIN_GAP_TICKS) {
            lastStartSoundTick = nowTick;
            countdownStartSoundsPlayed++;
            com.killer560.hub.dungeonalerts.DungeonAlertsFeature.playSound(
                    com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(cfg.getCountdownStartSoundId()).sound(),
                    volume, 1.0f);
        }
    }

    /** Trigger Bot clicks sent / of them attacks, and the last one described (the testkit reads these). */
    public static int triggerClicks() {
        return triggerClicks;
    }

    public static int triggerAttacks() {
        return triggerAttacks;
    }

    public static String lastTrigger() {
        return lastTrigger;
    }

    /** How many Countdown Start / Kill sounds actually played (the testkit reads these; its client is muted). */
    public static int countdownStartSoundsPlayed() {
        return countdownStartSoundsPlayed;
    }

    public static int killSoundsPlayed() {
        return killSoundsPlayed;
    }

    public static double lastKillableAt() {
        return lastKillableAt;
    }

    public static int lastKillablePingMs() {
        return lastKillablePingMs;
    }

    public static double lastTriggerAt() {
        return lastTriggerAt;
    }

    /** Fallback Watcher detection - see the call site for why the equipment packet alone is not enough. */
    private static void scanForWatcher(Minecraft client) {
        if (++watcherScanCounter < WATCHER_SCAN_INTERVAL_TICKS) {
            return;
        }
        watcherScanCounter = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Zombie zombie)) {
                continue;
            }
            String texture = getSkullTexture(zombie.getItemBySlot(EquipmentSlot.HEAD));
            if (texture != null && WATCHER_SKULL_TEXTURES.contains(texture)) {
                watcherEntityId = zombie.getId();
                return;
            }
        }
    }

    /** What the Trigger Bot treats as "the right hitbox" - a mob-sized box on the predicted landing spot, reaching up
     *  to the top of the box Blood Camp draws (1.5 to 2.5 above the spot), so aiming at the drawn box counts too. */
    static AABB aimBox(Vec3 end) {
        return new AABB(end.x - 0.5, end.y, end.z - 0.5, end.x + 0.5, end.y + 2.5, end.z + 0.5);
    }

    /**
     * Whether the crosshair ray meets {@code box} within reach.
     * <p>
     * Real bug found and fixed (2026-09-21) - killer560: "For blood camp triggerbot It didn't work." The old check
     * was {@code client.hitResult instanceof EntityHitResult hit && hit.getEntity() == trackedStand}, and this
     * repo already knows why that can never be true: {@code terminalaura.TerminalStands} says it outright - "a
     * marker stand has no pickable hitbox" so Hypixel's stands never appear in {@code Minecraft#hitResult}. The
     * Trigger Bot therefore had no reachable code path to a click at all. Ray-tracing the boxes ourselves is the
     * same fix Terminal Triggerbot already shipped.
     */
    private static boolean aimedAt(Minecraft client, AABB box) {
        Vec3 eyes = client.player.getEyePosition();
        AABB grown = box.inflate(AIM_INFLATE);
        // The BODY's look (getXRot/getYRot, what movement packets carry), never the view: in Freecam Mode the camera is
        // somewhere else. Both the look the last movement packet reported and the one this tick's will carry must hit.
        // And never through a wall (killer560, 2026-10-15): the look must reach the box before it meets a block.
        Vec3 dirNow = Vec3.directionFromRotation(client.player.getXRot(), client.player.getYRot());
        if (!reachesBeforeBlock(client, grown, eyes, dirNow)) {
            return false;
        }
        if (Float.isNaN(reportedYaw)) {
            return true;
        }
        return reachesBeforeBlock(client, grown, eyes, Vec3.directionFromRotation(reportedPitch, reportedYaw));
    }

    /** Whether the ray from {@code eye} along {@code dir} meets {@code box} within AIM_RANGE before any block. */
    private static boolean reachesBeforeBlock(Minecraft client, AABB box, Vec3 eye, Vec3 dir) {
        Vec3 end = eye.add(dir.scale(AIM_RANGE));
        Vec3 hit = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
        if (hit == null) {
            return false;
        }
        return hit.distanceTo(eye) <= blockDistance(client, eye, dir, AIM_RANGE) + 1e-6;
    }

    /**
     * The entity to actually swing at for a blood mob that is due.
     * <p>
     * The tracked {@link ArmorStand} is what Hypixel moves around, but the thing that takes damage once the mob
     * materialises is a real mob entity at the same spot, and attacking the stand instead is a wasted swing. So
     * the nearest real, non-player living entity the crosshair ray meets near the prediction wins, and the stand
     * is only the fallback - and only when the crosshair is genuinely on the stand's own box, which a marker
     * stand (zero-size box) never satisfies, so that fallback quietly costs nothing when the stand is just a
     * spawn marker but still works on the other reading, where the stand IS the mob.
     *
     * @return the entity to attack within {@code range}, or null (the click is then a swing on air)
     */
    private static Entity resolveAttackTarget(Minecraft client, ArmorStand stand, Vec3 predicted, double range) {
        Vec3 eyes = client.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(client.player.getXRot(), client.player.getYRot());
        // Reach ends at the first block on the way: nothing is attacked through a wall.
        Vec3 end = eyes.add(dir.scale(Math.min(range, blockDistance(client, eyes, dir, range))));
        AABB search = new AABB(predicted, predicted).inflate(TARGET_SEARCH_RADIUS);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(client.player, search, BloodCampFeature::isBloodMob)) {
            Vec3 hit = candidate.getBoundingBox().inflate(AIM_INFLATE).clip(eyes, end).orElse(null);
            if (hit == null) {
                continue;
            }
            double distance = eyes.distanceToSqr(hit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        return stand.getBoundingBox().inflate(AIM_INFLATE).clip(eyes, end).isPresent() ? stand : null;
    }

    /** A mob to hit: alive, not a stand, and not a real player - but a player-model NPC (Hypixel gives those a version-2
     *  UUID, real players version 4, the same test {@code leapcounter.LeapTracker} uses) is a mob. */
    private static boolean isAttackableMob(Entity entity) {
        return entity instanceof LivingEntity && entity.isAlive() && !entity.isRemoved()
                && !(entity instanceof ArmorStand)
                && !(entity instanceof Player && entity.getUUID().version() == 4)
                && entity != Minecraft.getInstance().player;
    }

    /** Real countdown formula, ported directly from Noamm's own real math (see this class's own doc
     *  comment) - real ticks remaining until this specific blood mob has settled and become vulnerable
     *  again. */
    private static double remainingTicks(BloodMobState data, long nowTick) {
        long timeTook = nowTick - data.startedAtTick;
        return (data.firstSpawn ? 40 : 0) + 38 - timeTook + 0.8;
    }

    /**
     * The Trigger Bot's first click goes on the first tick whose time left is below this. The kill timer (box green,
     * Kill Sound) goes off on the first tick whose time left is below the ping in ticks ({@link #killable}), so with
     * Auto Detect Lag on this is that moment moved by Trigger Offset: offset 0 clicks on the kill timer's own tick,
     * -1 (the default) one tick after it, +2 two ticks before. With Auto Detect Lag off the ping is left out and the
     * offset counts from the countdown's own end. The time left falls by exactly one a tick, so the click lands exactly
     * -offset ticks after the kill timer (testkit 792 and the 1015 blood camp cases measure it).
     */
    static double triggerTargetTicks(BloodCampConfig cfg, Minecraft client) {
        return autoLagOffsetTicks(cfg, client) + cfg.getTriggerOffsetTicks();
    }

    private static double autoLagOffsetTicks(BloodCampConfig cfg, Minecraft client) {
        if (!cfg.isAutoDetectLag() || client.getConnection() == null || client.player == null) {
            return 0;
        }
        return pingMs(client) / 50.0;
    }

    // ------------------------------------------------------------------
    // Rendering - real box/line/timer overlay, ported from Noamm's own real render layout
    // ------------------------------------------------------------------

    private static void onWorldRender(LevelRenderContext context) {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isShowOverlay() || !isActive()) {
            if (!LAST_FRAME.isEmpty()) {
                LAST_FRAME.clear();
            }
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // The look of NoammAddons' Blood Camp helper (killer560, 2026-10-07: "nearly identical to noamm's in look and
        // function - I don't quite like how ours looks"), reimplemented: per mob, a 1x1x1 OUTLINE box (2.5 px, no
        // fill) from 1.5 to 2.5 above the predicted landing spot, magenta, turning green once your ping exceeds the
        // time left; a cyan 2 px line from the stand's INTERPOLATED position + 2 (the skull in the wall, every frame,
        // not the last tick's spot) to the landing spot + 2; and the time left, white with a shadow, 0.05 per pixel
        // (Timer Text Scale 2 x 0.025), centred on the landing spot + 2 and hanging down from it. All of it draws
        // through walls, from the first move packet until the stand is removed. Spawn Line and its width, Timer Text
        // Scale and Show Overlay still switch their part.
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int pingMs = pingMs(client);
        long now = client.level.getGameTime();
        List<double[]> frame = new ArrayList<>();
        for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
            BloodMobState data = entry.getValue();
            if (data.endVector == null) {
                continue;
            }
            Vec3 end = data.endVector;
            AABB box = new AABB(end.x - 0.5, end.y + 1.5, end.z - 0.5, end.x + 0.5, end.y + 2.5, end.z + 0.5);
            double remainingTicks = remainingTicks(data, now);
            boolean killable = killable(remainingTicks, pingMs);
            int color = killable ? BOX_COLOR_KILLABLE : BOX_COLOR;
            float[] rgba = WorldRenderUtils.argbToFloats(color);
            WorldRenderUtils.renderOutlineBoxes(context, new AABB[]{box}, rgba, 1, BOX_LINE_WIDTH, true);
            Vec3 lineStart = null;
            Vec3 lineEnd = new Vec3(end.x, end.y + 2.0, end.z);
            if (cfg.isSpawnLine()) {
                lineStart = entry.getKey().getPosition(partial).add(0.0, 2.0, 0.0);
                float[] line = WorldRenderUtils.argbToFloats(LINE_COLOR);
                WorldRenderUtils.renderLine(context, lineStart, lineEnd, line[0], line[1], line[2], 1f,
                        cfg.getSpawnLineWidth(), true);
            }
            String text = timerText(remainingTicks);
            renderTimerText(context, end.x, end.y + 2.0, end.z, text);
            frame.add(new double[]{
                    lineStart == null ? Double.NaN : lineStart.x, lineStart == null ? Double.NaN : lineStart.y,
                    lineStart == null ? Double.NaN : lineStart.z, lineEnd.x, lineEnd.y, lineEnd.z,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, remainingTicks,
                    entry.getKey().getId()});
        }
        LAST_FRAME.clear();
        LAST_FRAME.addAll(frame);
        LAST_FRAME_TEXT.clear();
        for (double[] f : frame) {
            LAST_FRAME_TEXT.add(timerText(f[13]));
        }
    }

    /** Noamm's box colour (255, 0, 255), and its invert once the mob is due within your ping. */
    static final int BOX_COLOR = 0xFFFF00FF;
    static final int BOX_COLOR_KILLABLE = 0xFF00FF00;
    /** Noamm's line colour, {@code Color.CYAN}. */
    static final int LINE_COLOR = 0xFF00FFFF;
    /** Noamm's {@code renderBoxBounds} default line width. */
    static final float BOX_LINE_WIDTH = 2.5f;

    /** What the last frame drew, one row per mob: line start xyz (NaN with Spawn Line off), line end xyz, box
     *  min xyz, box max xyz, ARGB colour, ticks left, stand entity id. For the testkit; rebuilt every frame. */
    private static final List<double[]> LAST_FRAME = new ArrayList<>();
    private static final List<String> LAST_FRAME_TEXT = new ArrayList<>();

    public static List<double[]> lastFrame() {
        return new ArrayList<>(LAST_FRAME);
    }

    public static List<String> lastFrameText() {
        return new ArrayList<>(LAST_FRAME_TEXT);
    }

    /** "Kill now": your ping in ms is more than the time left (Noamm's colour flip). With no ping known, at zero. */
    static boolean killable(double remainingTicks, int pingMs) {
        return pingMs > remainingTicks * 50.0;
    }

    /** The measured round trip ({@link com.killer560.hub.util.PingTracker#automationMs}: median of the last pongs,
     *  the tab-list value before the first), ms; 0 when not connected. */
    static int pingMs(Minecraft client) {
        if (client.getConnection() == null || client.player == null) {
            return 0;
        }
        return com.killer560.hub.util.PingTracker.automationMs();
    }

    /** Noamm's label: the seconds left WITHOUT the +0.8-tick fudge the countdown carries, rounded half-up to one
     *  decimal, no unit ("1.9", "-0.3"). */
    static String timerText(double remainingTicks) {
        double seconds = (remainingTicks - 0.8) / 20.0;
        double rounded = Math.round(seconds * 10.0) / 10.0;
        return String.format(Locale.US, "%.1f", rounded);
    }

    private static void renderTimerText(LevelRenderContext context, double worldX, double worldY, double worldZ, String text) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Vec3 cam = McRender.cameraPos(context);
        // Noamm draws at scale 2 x 0.025; Timer Text Scale (default 2.0) is that multiplier.
        float scale = 0.025f * BloodCampConfig.getInstance().getTimerTextScale();

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(McRender.cameraRotation(context));
        // (+s, -s, +s): the 26.1.2 nametag transform - see SimonSaysFeature.renderNumber for why not (-s, -s, s).
        poseStack.scale(scale, -scale, scale);

        float width = font.width(text);
        // Centred across, hanging DOWN from the anchor (y 0 is the top of the glyphs), with a shadow and no
        // background plate, through walls - as Noamm's renderString draws it.
        McRender.drawText(context, font, text, -width / 2f, 0f, 0xFFFFFFFF, true, poseStack,
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);

        poseStack.popPose();
    }
}
