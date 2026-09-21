package com.killer560.hub.petwheel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.itemprotect.ItemProtect;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively reads whatever page of the real {@code /pets} menu is currently open into
 * {@link PetWheelConfig#recordSeenPet}, so {@link PetPickerScreen} has real pets to choose from - killer560's
 * "read his pets the next time he opens /pets" - without this class ever clicking anything itself (that part
 * is {@link PetSummoner}, driven by a wheel selection, not by opening the menu).
 * <p>
 * Real title regex ported verbatim from this repo's own already-verified copies ({@code MaskSwapper.PETS_TITLE},
 * itself off a real screenshot via the deleted {@code i4sensors.I4AutoMask}, and independently
 * {@code experiments.GuardianPetSwapper.PETS_TITLE_PATTERN} off a real screenshot from 2026-09-06): the real
 * {@code /pets} screen is titled exactly {@code "Pets"} or {@code "(N/M) Pets"} (page prefix, not suffix) - not
 * re-derived here, reused because two independent real-screenshot checks in this codebase already agree on it.
 */
public final class PetsMenuScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-petwheel-scan");

    private static final Pattern PETS_TITLE = Pattern.compile("^(?:\\((\\d+)/(\\d+)\\)\\s*)?Pets$");
    /** Devonian's own compiled pet-name regex confirms this exact bracket format: {@code "[Lvl N] Name"}. */
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[Lvl (\\d+)]\\s*(.*)$");
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private static int lastScannedContainerId = -1;

    private PetsMenuScanner() {
    }

    /** Called every client tick by {@link PetWheelFeature}; no-ops unless Pet Wheel is enabled and the real
     *  Pets screen is the one currently open - this package touches nothing while the feature is off. */
    public static void tick(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
            lastScannedContainerId = -1;
            return;
        }
        Matcher title = PETS_TITLE.matcher(screen.getTitle().getString());
        if (!title.matches()) {
            lastScannedContainerId = -1;
            return;
        }
        List<Slot> slots = screen.getMenu().slots;
        int containerSlots = Math.max(0, slots.size() - PLAYER_INVENTORY_SLOTS);
        boolean changed = false;
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        for (int i = 0; i < containerSlots; i++) {
            PetEntry entry = scanSlot(slots.get(i).getItem());
            if (entry != null && cfg.recordSeenPet(entry)) {
                changed = true;
            }
        }
        if (changed) {
            cfg.save();
        }
        lastScannedContainerId = screen.getMenu().containerId;
    }

    /** @return a {@link PetEntry} for a real pet head slot, or null (empty slot, not a pet head, or - Next
     *  Page/other navigation item - no identifiable {@code ExtraAttributes.uuid}). */
    static PetEntry scanSlot(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
            return null;
        }
        String uuid = ItemProtect.itemUuid(stack);
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        String plain = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        String name = plain == null ? null : plain.trim();
        int level = -1;
        if (name != null) {
            Matcher m = LEVEL_PREFIX.matcher(name);
            if (m.matches()) {
                level = Integer.parseInt(m.group(1));
                name = m.group(2).trim();
            }
        }
        String tier = null;
        CompoundTag extraAttributes = extraAttributes(stack);
        if (extraAttributes != null) {
            String petInfoJson = extraAttributes.getStringOr("petInfo", null);
            if (petInfoJson != null) {
                try {
                    JsonObject petInfo = JsonParser.parseString(petInfoJson).getAsJsonObject();
                    if (petInfo.has("tier") && !petInfo.get("tier").isJsonNull()) {
                        tier = petInfo.get("tier").getAsString();
                    }
                } catch (RuntimeException ignored) {
                    // petInfo isn't always present/parseable (e.g. a non-pet head shouldn't reach here anyway,
                    // but a malformed blob must never break the rest of the scan).
                }
            }
        }
        String skinValue = skullTexture(stack);
        return new PetEntry(uuid, name, tier, level, skinValue);
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** Same {@code PROFILE -> partialProfile() -> properties -> "textures"} chain {@code BloodCampFeature}
     *  already uses for real skull identification, stopping at the texture string itself. */
    private static String skullTexture(ItemStack stack) {
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile == null) {
            return null;
        }
        var partial = profile.partialProfile();
        if (partial == null) {
            return null;
        }
        Iterator<Property> it = partial.properties().get("textures").iterator();
        return it.hasNext() ? it.next().value() : null;
    }
}
