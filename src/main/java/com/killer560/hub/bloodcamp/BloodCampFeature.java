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
import net.minecraft.client.multiplayer.PlayerInfo;
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
 * Watcher's own move schedule ({@link BloodCampMoveTimer}), an auto-detected-lag or
 * manual tick offset for exactly when that click fires, a guaranteed single click per mob (never a
 * repeat), a spawn-overlay-only mode with no clicking at all, and an "aura" that turns to face the
 * predicted spot before the mob visually arrives. The aura's rotation math follows this mod's own
 * standing rule: Hypixel tracks a real player's yaw as a continuously running, uncapped value, so this
 * never wraps/clamps the stored rotation at 0-360 - it only ever computes a bounded DELTA (via
 * {@link Mth#wrapDegrees}) and adds that onto the player's existing real yaw, exactly the same way a
 * real 10-times-spun player's yaw would keep climbing past 3600 instead of resetting.
 */
public final class BloodCampFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodcamp");

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
    /** Said once per session, not every tick: his offset setting is being held back to "never early". */
    private static boolean loggedOffsetClamp;
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

    private static Integer watcherEntityId;
    private static final Map<ArmorStand, BloodMobState> bloodMobs = new HashMap<>();
    private static Object lastLevel = null;
    private static int watcherScanCounter = 0;

    private BloodCampFeature() {
    }

    public static void register() {
        WorldRenderUtils.initThroughWalls();
        BloodCampMoveTimer.register();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("BloodCampFeature", client -> tick()));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BloodCampFeature::onWorldRender);
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
                || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, element);
    }

    private static boolean isActive() {
        // Every floor's Blood Room, never the boss fight - NoammAddons' own gate. This used to be F7/M7 only,
        // which is why it worked in F7 and silently did nothing everywhere else.
        return BloodCampConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
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
        if (!cfg.isEnabled() || !isActive()) {
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
        bloodMobs.keySet().removeIf(entity -> packet.getEntityIds().contains(entity.getId()));
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
            bloodMobs.clear();
            watcherEntityId = null;
            BloodCampMoveTimer.reset();
            // a new level's game time can be behind the old one's; never let that hold a sound off
            lastStartSoundTick = Long.MIN_VALUE / 2;
            lastKillSoundTick = Long.MIN_VALUE / 2;
        }
        if (!isActive()) {
            if (!bloodMobs.isEmpty() || watcherEntityId != null) {
                bloodMobs.clear();
                watcherEntityId = null;
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

        tickSounds(cfg, client.level.getGameTime(), pingMs(client));

        Vec3 auraTarget = null;
        // killer560 (2026-09-20): "if I have two levers in my range at once ... have it only pick one and then the
        // other on the next tick". This loop used to attack EVERY due mob in the same pass, so a wave that expired
        // together produced a burst of attack packets on one tick. Pick the most-due one here and send it below.
        Entity triggerTarget = null;
        BloodMobState triggerData = null;
        double bestRemaining = Double.MAX_VALUE;
        // NEVER EARLY. killer560 (2026-09-27): "make it so it cannot triggerbot click before the kill
        // notification timing would go off. Then if my crosshair is looking in a blood mobs spawn hitbox when it
        // goes to spawn have it click once."
        //
        // The window used to OPEN at `manual offset + ping`, both of which lead the prediction, so the click went
        // out while remainingTicks was still positive - before the moment the kill notification is timed to. That
        // lead was deliberate once (a laggy connection fires early so the packet lands on time), and he has now
        // asked for the opposite rule, so the requested offset may only ever DELAY the click, never advance it.
        //
        // The cost is explicit and is his to choose: giving up the ping lead means the packet reaches Hypixel
        // roughly one ping AFTER the moment rather than on it. He asked for "cannot click before", and a click
        // that lands early is the thing he is ruling out.
        double requestedOffset = cfg.getManualTickOffset() + autoLagOffsetTicks(cfg, client);
        double clickAtTicks = Math.min(0.0, requestedOffset);
        if (requestedOffset > 0.0 && !loggedOffsetClamp) {
            loggedOffsetClamp = true;
            LOGGER.info("[BloodCamp] Trigger Bot offset {} tick(s) would fire BEFORE the kill-notification moment"
                            + " - held to 0. Early clicks are off by request (2026-09-27); a negative offset still"
                            + " delays normally.",
                    String.format(Locale.US, "%.1f", requestedOffset));
        }
        boolean triggerBotUsable = cfg.isTriggerBotEnabled()
                && !com.killer560.hub.util.ActionGate.containerScreenOpen(client);
        double reach = client.player.entityInteractionRange();
        for (Map.Entry<ArmorStand, BloodMobState> entry : bloodMobs.entrySet()) {
            ArmorStand entity = entry.getKey();
            BloodMobState data = entry.getValue();
            if (data.endVector == null || entity.isRemoved()) {
                continue;
            }
            double remainingTicks = remainingTicks(data, client.level.getGameTime());

            if (cfg.isAuraEnabled() && remainingTicks <= 30 && remainingTicks > 0 && auraTarget == null) {
                auraTarget = data.endVector;
            }

            if (!triggerBotUsable || data.triggerBotClicked) {
                continue;
            }
            // Only inside the window: due, but not so long ago that the prediction is stale.
            if (remainingTicks > clickAtTicks || remainingTicks <= clickAtTicks - TRIGGER_WINDOW_TICKS
                    || remainingTicks >= bestRemaining) {
                continue;
            }
            // "if my crosshair is looking in a blood mobs spawn hitbox when it goes to spawn have it click once."
            // aimBox is that spawn hitbox - a 1x2x1 box standing on the predicted spawn point. The stand's own
            // bounding box is accepted too, so being aimed at the real mob once it exists counts as well; it can
            // only ever ADD a case he would want, never fire somewhere he is not looking. One click per mob is
            // enforced by triggerBotClicked above, which is only ever set after a click actually goes out.
            if (!aimedAt(client, aimBox(data.endVector), reach)
                    && !aimedAt(client, entity.getBoundingBox(), reach)) {
                continue;
            }
            Entity target = resolveAttackTarget(client, entity, data.endVector, reach);
            if (target == null) {
                continue;
            }
            triggerTarget = target;
            triggerData = data;
            bestRemaining = remainingTicks;
        }
        // Mod-wide one-interaction-per-tick gate, after the target is chosen and before anything is marked: a
        // denial leaves triggerBotClicked false so the same mob is simply hit on the next tick it allows.
        if (triggerTarget != null
                && com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.BLOOD_CAMP)) {
            client.gameMode.attack(client.player, triggerTarget);
            client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            triggerData.triggerBotClicked = true;
        }

        if (cfg.isAuraEnabled() && auraTarget != null) {
            lookTowardsSafely(client, auraTarget);
        }
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

    /** How many Countdown Start / Kill sounds actually played (the testkit reads these; its client is muted). */
    public static int countdownStartSoundsPlayed() {
        return countdownStartSoundsPlayed;
    }

    public static int killSoundsPlayed() {
        return killSoundsPlayed;
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

    /** What the Trigger Bot treats as "the right hitbox" - a mob-sized box on the predicted landing spot. The
     *  overlay's own box is deliberately not reused: it only covers the head so it reads well from a distance. */
    private static AABB aimBox(Vec3 end) {
        return new AABB(end.x - 0.5, end.y, end.z - 0.5, end.x + 0.5, end.y + 2.0, end.z + 0.5);
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
    private static boolean aimedAt(Minecraft client, AABB box, double range) {
        Vec3 eyes = client.player.getEyePosition();
        Vec3 end = eyes.add(client.player.getViewVector(1f).scale(range));
        return box.inflate(AIM_INFLATE).clip(eyes, end).isPresent();
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
     * @return the entity to attack, or null to try again next tick rather than swing at nothing
     */
    private static Entity resolveAttackTarget(Minecraft client, ArmorStand stand, Vec3 predicted, double range) {
        Vec3 eyes = client.player.getEyePosition();
        Vec3 end = eyes.add(client.player.getViewVector(1f).scale(range));
        AABB search = new AABB(predicted, predicted).inflate(TARGET_SEARCH_RADIUS);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(client.player, search, BloodCampFeature::isAttackableMob)) {
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

    private static boolean isAttackableMob(Entity entity) {
        return entity instanceof LivingEntity && entity.isAlive() && !entity.isRemoved()
                && !(entity instanceof ArmorStand) && !(entity instanceof Player);
    }

    /** Real countdown formula, ported directly from Noamm's own real math (see this class's own doc
     *  comment) - real ticks remaining until this specific blood mob has settled and become vulnerable
     *  again. */
    private static double remainingTicks(BloodMobState data, long nowTick) {
        long timeTook = nowTick - data.startedAtTick;
        return (data.firstSpawn ? 40 : 0) + 38 - timeTook + 0.8;
    }

    /** Real ping-based auto lag compensation (killer560's own request) - fire the click that many ticks
     *  earlier than the manual offset alone would, so the real click PACKET (not just the local
     *  prediction) lands around the real moment the mob actually becomes vulnerable server-side, the same
     *  real idea Noamm's own reference uses ping for (inverting the highlight box color as a warning) but
     *  applied here to the actual click timing instead of just a visual warning.
     *  <p>
     *  The caller ADDS this to the manual offset (fixed 2026-09-21 - it used to subtract it, which delayed
     *  the click by a full round trip instead of advancing it). Latency here is the tab-list round-trip
     *  ping; half of it would be the one-way lead, so this is deliberately generous. */
    private static double autoLagOffsetTicks(BloodCampConfig cfg, Minecraft client) {
        if (!cfg.isAutoDetectLag() || client.getConnection() == null || client.player == null) {
            return 0;
        }
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        if (info == null) {
            return 0;
        }
        return info.getLatency() / 50.0;
    }

    /** Turns the player to face {@code target}, a fraction of the way per tick (smooth, not a snap) -
     *  see this class's own doc comment for the real "never wrap/clamp at 0-360" rule this follows: only
     *  a bounded DELTA is ever computed (via {@link Mth#wrapDegrees}), which then gets ADDED onto the
     *  player's own existing, continuously-running real yaw/pitch - never replacing it with a freshly
     *  bounded absolute angle, which would look like an impossible sudden jump on Hypixel's own real,
     *  uncapped rotation tracking. */
    private static void lookTowardsSafely(Minecraft client, Vec3 target) {
        var player = client.player;
        Vec3 eyePos = player.getEyePosition();
        Vec3 diff = target.subtract(eyePos);
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float rawTargetYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float rawTargetPitch = (float) -(Mth.atan2(diff.y, horizontalDist) * (180.0 / Math.PI));

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float yawDelta = Mth.wrapDegrees(rawTargetYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(rawTargetPitch - currentPitch);

        float smoothing = 0.15f;
        player.setYRot(currentYaw + yawDelta * smoothing);
        player.setXRot(currentPitch + pitchDelta * smoothing);
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

    /** Tab-list round trip for the local player, ms; 0 when unknown. */
    private static int pingMs(Minecraft client) {
        if (client.getConnection() == null || client.player == null) {
            return 0;
        }
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
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
