package com.killer560.hub.privacy;

import com.killer560.hub.BuildVariant;
import net.minecraft.client.KeyMapping;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a server from learning that this mod is installed through text the client resolves and sends back.
 * <p>
 * A server can put a translatable or keybind component on a sign, an anvil item name or a book and read the
 * resolved string back (sign_update, rename_item). A client without the mod answers with the raw key; a
 * client whose language or key mappings know the key answers with the text, which names the mod. So:
 * <ul>
 *   <li>language: no resource from this mod's own pack is ever read into the language table
 *       ({@code privacy/mixin/ClientLanguageMixin}); a server resource pack's translations still load as on
 *       any client;</li>
 *   <li>key mappings: a mapping created by this mod's code answers a keybind component as if it did not
 *       exist ({@code privacy/mixin/KeyMappingPresenceMixin}); the Controls screen still shows its name,
 *       because it reads the mapping directly, not through the keybind resolver.</li>
 * </ul>
 * The build ({@code checkNoFingerprint} in build.gradle) and {@code tools/verify-artifact.py} refuse a jar
 * that ships a lang file or a {@code data/} folder, uses a mod-only translation key or registers a global
 * payload receiver; testkit 65-smoke-join-fingerprint probes the live client on the wire.
 */
public final class ModPresence {

    private static final String PACK_ID = "killer560smod";
    private static final String OWN_PACKAGE = "com.killer560.";
    /** Names of key mappings this mod created. Boot-time only (one entry per mapping), so it stays small. */
    private static final Set<String> OWN_MAPPINGS = ConcurrentHashMap.newKeySet();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private ModPresence() {
    }

    /** True for this mod's own resource pack (Fabric names it after the mod id, sub packs {@code id_sub}). */
    public static boolean ownPack(String packId) {
        return packId != null && (packId.equals(PACK_ID) || packId.startsWith(PACK_ID + "_"));
    }

    /** Called once per KeyMapping constructed: remembers it when this mod's code built it. */
    public static void noteMapping(String name) {
        if (name == null) return;
        // every frame but this method's own: a mapping built in this class (the test probe) counts as ours
        boolean ours = WALKER.walk(s -> s.skip(1).anyMatch(f -> f.getClassName().startsWith(OWN_PACKAGE)));
        if (ours) OWN_MAPPINGS.add(name);
    }

    /** The keybind resolver's lookup: a mapping of ours reads as absent, as on a client without the mod. */
    public static Object hideMapping(Object mapping) {
        return mapping instanceof KeyMapping km && OWN_MAPPINGS.contains(km.getName()) ? null : mapping;
    }

    /** Names of the key mappings this mod created (the testkit probes each). */
    public static Set<String> ownMappings() {
        return Set.copyOf(OWN_MAPPINGS);
    }

    /** Test hook: builds a key mapping from mod code, so the testkit can prove the keybind guard acts. */
    public static KeyMapping probeMappingForTest(String name) {
        if (!BuildVariant.DEV_TOOLS) return null;
        return new KeyMapping(name, -1, KeyMapping.Category.MISC);
    }
}
