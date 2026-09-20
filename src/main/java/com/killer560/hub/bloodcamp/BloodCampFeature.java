package com.killer560.hub.bloodcamp;

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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Blood Camp - killer560's request: "use noamm's blood mob tracer thing it already has, then add a
 * triggerbot option so if i am looking at the right hitbox as the timer expires then it will left click
 * on the mob." Real mechanic and every constant below ported directly from Noamm's own confirmed,
 * compiling {@code BloodCamp.kt} (cloned reference, 2026-09-14) - the real F7 boss-fight "Blood Camp"
 * mechanic: a real {@code Zombie} wearing a real specific player-head skin is the Watcher, and real
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
 * countdown expires AND the player's real crosshair is on that exact mob), an auto-detected-lag or
 * manual tick offset for exactly when that click fires, a guaranteed single click per mob (never a
 * repeat), a spawn-overlay-only mode with no clicking at all, and an "aura" that turns to face the
 * predicted spot before the mob visually arrives. The aura's rotation math follows this mod's own
 * standing rule: Hypixel tracks a real player's yaw as a continuously running, uncapped value, so this
 * never wraps/clamps the stored rotation at 0-360 - it only ever computes a bounded DELTA (via
 * {@link Mth#wrapDegrees}) and adds that onto the player's existing real yaw, exactly the same way a
 * real 10-times-spun player's yaw would keep climbing past 3600 instead of resetting.
 */
public final class BloodCampFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bloodcamp");

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

    private static Integer watcherEntityId;
    private static final Map<ArmorStand, BloodMobState> bloodMobs = new HashMap<>();
    private static boolean firstSpawns = true;

    private BloodCampFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BloodCampFeature::onWorldRender);
    }

    private static boolean isActive() {
        return BloodCampConfig.getInstance().isEnabled() && DungeonState.isF7OrM7();
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
            LOGGER.info("[BloodCamp] Watcher detected: entityId={} (thread={})", watcherEntityId, Thread.currentThread().getName());
        }
    }

    // [BloodCamp] diagnostics - logging only.
    private static String lastLoggedGates = null;
    private static int lastLoggedMobCount = -1;
    private static boolean lastLoggedAuraActive = false;

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

        Vec3 packetVec = new Vec3(
                entity.getX() + packet.getXa() / 4096.0,
                entity.getY() + packet.getYa() / 4096.0,
                entity.getZ() + packet.getZa() / 4096.0
        );

        if (!bloodMobs.containsKey(entity)) {
            LOGGER.info("[BloodCamp] New blood mob tracked: entityId={} pos={} firstSpawn={} (thread={})",
                    entity.getId(), entity.blockPosition(), firstSpawns, Thread.currentThread().getName());
        }
        BloodMobState data = bloodMobs.computeIfAbsent(entity,
                e -> new BloodMobState(packetVec, client.level != null ? client.level.getGameTime() : 0L, firstSpawns));
        firstSpawns = false;

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
        if (total.lengthSqr() > 0) {
            data.endVector = data.startVec.add(total.normalize().scale(spawnScale));
        }
    }

    public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, Level level) {
        if (watcherEntityId != null && packet.getEntityIds().contains(watcherEntityId)) {
            LOGGER.info("[BloodCamp] Watcher entity {} removed (thread={})", watcherEntityId, Thread.currentThread().getName());
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
        String gates = "enabled=" + cfg.isEnabled() + " f7OrM7=" + DungeonState.isF7OrM7() + " overlay=" + cfg.isShowOverlay()
                + " triggerBot=" + cfg.isTriggerBotEnabled() + " aura=" + cfg.isAuraEnabled()
                + " watcherId=" + watcherEntityId;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[BloodCamp] Gates changed: {} (active={})", gates, isActive());
            lastLoggedGates = gates;
        }
        if (bloodMobs.size() != lastLoggedMobCount) {
            LOGGER.info("[BloodCamp] Tracked blood mobs: {} -> {}", lastLoggedMobCount, bloodMobs.size());
            lastLoggedMobCount = bloodMobs.size();
        }
        if (!isActive()) {
            if (!bloodMobs.isEmpty() || watcherEntityId != null) {
                bloodMobs.clear();
                watcherEntityId = null;
                firstSpawns = true;
            }
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        Vec3 auraTarget = null;
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

            if (cfg.isTriggerBotEnabled() && !data.triggerBotClicked) {
                double clickAtTicks = cfg.getManualTickOffset() - autoLagOffsetTicks(cfg, client);
                if (remainingTicks <= clickAtTicks && isLookingAt(client, entity)) {
                    client.gameMode.attack(client.player, entity);
                    client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    data.triggerBotClicked = true;
                    LOGGER.info("[BloodCamp] Trigger Bot clicked a blood mob (remaining {} ticks at click time).",
                            String.format(Locale.US, "%.1f", remainingTicks));
                }
            }
        }

        if ((cfg.isAuraEnabled() && auraTarget != null) != lastLoggedAuraActive) {
            lastLoggedAuraActive = cfg.isAuraEnabled() && auraTarget != null;
            LOGGER.info("[BloodCamp] Aura rotation active={} target={}", lastLoggedAuraActive, auraTarget);
        }
        if (cfg.isAuraEnabled() && auraTarget != null) {
            lookTowardsSafely(client, auraTarget);
        }
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
     *  applied here to the actual click timing instead of just a visual warning. */
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

    private static boolean isLookingAt(Minecraft client, Entity entity) {
        return client.hitResult instanceof EntityHitResult hit && hit.getEntity() == entity;
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
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        for (BloodMobState data : bloodMobs.values()) {
            if (data.endVector == null) {
                continue;
            }
            Vec3 end = data.endVector;
            AABB box = new AABB(end.x - 0.5, end.y + 1.0, end.z - 0.5, end.x + 0.5, end.y + 2.0, end.z + 0.5);
            double remainingTicks = remainingTicks(data, client.level.getGameTime());
            int color = remainingTicks <= 0 ? 0xFF55FF55 : 0xFFFF55FF;
            float[] rgba = WorldRenderUtils.argbToFloats(color);
            WorldRenderUtils.renderOutlineBox(context, box, rgba[0], rgba[1], rgba[2], 1f, 2f);
            renderTimerText(context, end.x, end.y + 2.3, end.z, remainingTicks / 20.0);
        }
    }

    private static void renderTimerText(LevelRenderContext context, double worldX, double worldY, double worldZ, double seconds) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        var mainCamera = client.gameRenderer.getMainCamera();
        Vec3 cam = mainCamera.position();
        String text = String.format(Locale.US, "%.1fs", seconds);
        float scale = 0.02f;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(mainCamera.rotation());
        // Real bug found and fixed (2026-09-14): scaled by (-s, -s, s), the pre-1.21.2 nametag transform.
        // On 26.1.2 the camera quaternion is already flipped and vanilla's nametag renderer uses
        // (+s, -s, +s); the extra -X mirror reversed the glyph quads' winding so the (culled) text
        // pipelines back-face culled the timer 100% of the time. Same fix as SimonSaysFeature.renderNumber.
        poseStack.scale(scale, -scale, scale);

        float width = font.width(text);
        int background = (int) (0.4f * 255f) << 24;
        // -lineHeight/2 so the timer is vertically centered on its anchor instead of hanging below it.
        font.drawInBatch(text, -width / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);

        poseStack.popPose();
    }
}
