package com.killer560.hub.crosshair;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Custom Crosshair settings - killer560, 2026-10-06: "add a custom crosshair feature, I should be able to fully edit my
 * crosshair in just about every way." A CS2/Valorant-style editor: shape, every dimension, colours, outline, dynamic
 * behaviour and visibility, plus named presets saved beside this file and a short share code.
 * <p>
 * Sizes are in crosshair units: one unit is one GUI pixel of the baseline (2560x1440 at GUI Scale 3, so three screen
 * pixels there) in {@link SizeMode#GUI}, and one screen pixel in {@link SizeMode#PIXELS}. The renderer rounds every
 * size to whole screen pixels, so lines stay crisp at any GUI scale. Setters clamp to the slider ranges and never snap,
 * so the testkit's setter round trip reads back exactly what it set.
 */
public final class CustomCrosshairConfig {

    /** Shapes. Append only: the share code stores the ordinal. */
    public enum Style {
        CROSS("Cross"), T_SHAPE("T-Shape"), X("X"), DOT("Dot"), CIRCLE("Circle"), CROSS_CIRCLE("Cross + Circle");

        public final String label;

        Style(String label) {
            this.label = label;
        }

        public boolean hasArms() {
            return this == CROSS || this == T_SHAPE || this == X || this == CROSS_CIRCLE;
        }

        public boolean hasCircle() {
            return this == CIRCLE || this == CROSS_CIRCLE;
        }
    }

    /** What one crosshair unit is. Append only (share code ordinal). */
    public enum SizeMode {
        /** One baseline GUI pixel: follows the GUI Scale, and the monitor too while the mod's Auto Scale is on. */
        GUI("GUI Scale"),
        /** One screen pixel, whatever the monitor or GUI Scale. */
        PIXELS("Screen Pixels");

        public final String label;

        SizeMode(String label) {
            this.label = label;
        }
    }

    public static final float MIN_LENGTH = 0f, MAX_LENGTH = 40f;
    public static final float MIN_THICKNESS = 0.5f, MAX_THICKNESS = 10f;
    public static final float MIN_GAP = -10f, MAX_GAP = 20f;
    public static final float MIN_DOT = 0.5f, MAX_DOT = 10f;
    public static final float MIN_RADIUS = 1f, MAX_RADIUS = 50f;
    public static final float MIN_RING = 0.5f, MAX_RING = 10f;
    public static final float MIN_ROTATION = 0f, MAX_ROTATION = 359f;
    public static final float MIN_SCALE = 0.25f, MAX_SCALE = 4f;
    public static final float MIN_OUTLINE = 0.5f, MAX_OUTLINE = 5f;
    public static final float MIN_CHROMA = 0.1f, MAX_CHROMA = 5f;
    public static final float MIN_SPREAD = 0f, MAX_SPREAD = 20f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-crosshair.json");
    /** Named presets, one JSON per preset. A sub-folder, so Profiles' live-file scan never mistakes one for a setting. */
    private static final Path PRESETS_DIR = ModPaths.config("killer560smod-crosshair-presets");
    /** Share code prefix; the digit is the format version. */
    public static final String CODE_PREFIX = "KXH1-";

    private static CustomCrosshairConfig instance;

    private boolean enabled = false;
    private Style style = Style.CROSS;
    private SizeMode sizeMode = SizeMode.GUI;
    private boolean dot = false;
    private boolean armTop = true;
    private boolean armBottom = true;
    private boolean armLeft = true;
    private boolean armRight = true;
    private float length = 4f;
    private float thickness = 1f;
    private float gap = 2f;
    private float dotSize = 1f;
    private float circleRadius = 6f;
    private float circleThickness = 1f;
    private float rotation = 0f;
    private float scale = 1f;
    private int color = 0xFFFFFFFF;
    private boolean separateDotColor = false;
    private int dotColor = 0xFFFF3030;
    private boolean chroma = false;
    private float chromaSpeed = 1f;
    private boolean invertBlend = false;
    private boolean outline = true;
    private float outlineThickness = 1f;
    private int outlineColor = 0xFF000000;
    private boolean spreadMoving = false;
    private boolean spreadSprinting = false;
    private boolean spreadJumping = false;
    private float spreadAmount = 3f;
    private boolean recoil = false;
    private float recoilAmount = 3f;
    private boolean colorOnEntity = false;
    private int entityColor = 0xFFFF3030;
    private boolean colorOnBlock = false;
    private int blockColor = 0xFF40B0FF;
    private boolean attackIndicator = true;
    private boolean hideInMenus = false;
    private boolean showInThirdPerson = false;

    private CustomCrosshairConfig() {
    }

    public static CustomCrosshairConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    private static float clamp(float v, float min, float max) {
        if (!Float.isFinite(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    // ---- getters / setters ---------------------------------------------------------------------------------------

    /** The master switch through the "Skyblock Only" gate, like every other feature. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style v) {
        style = v == null ? Style.CROSS : v;
    }

    public SizeMode getSizeMode() {
        return sizeMode;
    }

    public void setSizeMode(SizeMode v) {
        sizeMode = v == null ? SizeMode.GUI : v;
    }

    public boolean isDot() {
        return dot;
    }

    public void setDot(boolean v) {
        dot = v;
    }

    public boolean isArmTop() {
        return armTop;
    }

    public void setArmTop(boolean v) {
        armTop = v;
    }

    public boolean isArmBottom() {
        return armBottom;
    }

    public void setArmBottom(boolean v) {
        armBottom = v;
    }

    public boolean isArmLeft() {
        return armLeft;
    }

    public void setArmLeft(boolean v) {
        armLeft = v;
    }

    public boolean isArmRight() {
        return armRight;
    }

    public void setArmRight(boolean v) {
        armRight = v;
    }

    public float getLength() {
        return length;
    }

    public void setLength(float v) {
        length = clamp(v, MIN_LENGTH, MAX_LENGTH);
    }

    public float getThickness() {
        return thickness;
    }

    public void setThickness(float v) {
        thickness = clamp(v, MIN_THICKNESS, MAX_THICKNESS);
    }

    public float getGap() {
        return gap;
    }

    public void setGap(float v) {
        gap = clamp(v, MIN_GAP, MAX_GAP);
    }

    public float getDotSize() {
        return dotSize;
    }

    public void setDotSize(float v) {
        dotSize = clamp(v, MIN_DOT, MAX_DOT);
    }

    public float getCircleRadius() {
        return circleRadius;
    }

    public void setCircleRadius(float v) {
        circleRadius = clamp(v, MIN_RADIUS, MAX_RADIUS);
    }

    public float getCircleThickness() {
        return circleThickness;
    }

    public void setCircleThickness(float v) {
        circleThickness = clamp(v, MIN_RING, MAX_RING);
    }

    public float getRotation() {
        return rotation;
    }

    public void setRotation(float v) {
        rotation = clamp(v, MIN_ROTATION, MAX_ROTATION);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float v) {
        scale = clamp(v, MIN_SCALE, MAX_SCALE);
    }

    public int getColor() {
        return color;
    }

    public void setColor(int v) {
        color = v;
    }

    public boolean isSeparateDotColor() {
        return separateDotColor;
    }

    public void setSeparateDotColor(boolean v) {
        separateDotColor = v;
    }

    public int getDotColor() {
        return dotColor;
    }

    public void setDotColor(int v) {
        dotColor = v;
    }

    public boolean isChroma() {
        return chroma;
    }

    public void setChroma(boolean v) {
        chroma = v;
    }

    public float getChromaSpeed() {
        return chromaSpeed;
    }

    public void setChromaSpeed(float v) {
        chromaSpeed = clamp(v, MIN_CHROMA, MAX_CHROMA);
    }

    public boolean isInvertBlend() {
        return invertBlend;
    }

    public void setInvertBlend(boolean v) {
        invertBlend = v;
    }

    public boolean isOutline() {
        return outline;
    }

    public void setOutline(boolean v) {
        outline = v;
    }

    public float getOutlineThickness() {
        return outlineThickness;
    }

    public void setOutlineThickness(float v) {
        outlineThickness = clamp(v, MIN_OUTLINE, MAX_OUTLINE);
    }

    public int getOutlineColor() {
        return outlineColor;
    }

    public void setOutlineColor(int v) {
        outlineColor = v;
    }

    public boolean isSpreadMoving() {
        return spreadMoving;
    }

    public void setSpreadMoving(boolean v) {
        spreadMoving = v;
    }

    public boolean isSpreadSprinting() {
        return spreadSprinting;
    }

    public void setSpreadSprinting(boolean v) {
        spreadSprinting = v;
    }

    public boolean isSpreadJumping() {
        return spreadJumping;
    }

    public void setSpreadJumping(boolean v) {
        spreadJumping = v;
    }

    public float getSpreadAmount() {
        return spreadAmount;
    }

    public void setSpreadAmount(float v) {
        spreadAmount = clamp(v, MIN_SPREAD, MAX_SPREAD);
    }

    public boolean isRecoil() {
        return recoil;
    }

    public void setRecoil(boolean v) {
        recoil = v;
    }

    public float getRecoilAmount() {
        return recoilAmount;
    }

    public void setRecoilAmount(float v) {
        recoilAmount = clamp(v, MIN_SPREAD, MAX_SPREAD);
    }

    public boolean isColorOnEntity() {
        return colorOnEntity;
    }

    public void setColorOnEntity(boolean v) {
        colorOnEntity = v;
    }

    public int getEntityColor() {
        return entityColor;
    }

    public void setEntityColor(int v) {
        entityColor = v;
    }

    public boolean isColorOnBlock() {
        return colorOnBlock;
    }

    public void setColorOnBlock(boolean v) {
        colorOnBlock = v;
    }

    public int getBlockColor() {
        return blockColor;
    }

    public void setBlockColor(int v) {
        blockColor = v;
    }

    public boolean isAttackIndicator() {
        return attackIndicator;
    }

    public void setAttackIndicator(boolean v) {
        attackIndicator = v;
    }

    public boolean isHideInMenus() {
        return hideInMenus;
    }

    public void setHideInMenus(boolean v) {
        hideInMenus = v;
    }

    public boolean isShowInThirdPerson() {
        return showInThirdPerson;
    }

    public void setShowInThirdPerson(boolean v) {
        showInThirdPerson = v;
    }

    /** Any of the three movement spreads is on. */
    public boolean anySpread() {
        return spreadMoving || spreadSprinting || spreadJumping;
    }

    // ---- JSON ------------------------------------------------------------------------------------------------------

    /** Every appearance and behaviour field; {@code enabled} only when asked (a preset never switches the feature). */
    public JsonObject toJson(boolean includeEnabled) {
        JsonObject o = new JsonObject();
        if (includeEnabled) {
            o.addProperty("enabled", enabled);
        }
        o.addProperty("style", style.name());
        o.addProperty("sizeMode", sizeMode.name());
        o.addProperty("dot", dot);
        o.addProperty("armTop", armTop);
        o.addProperty("armBottom", armBottom);
        o.addProperty("armLeft", armLeft);
        o.addProperty("armRight", armRight);
        o.addProperty("length", length);
        o.addProperty("thickness", thickness);
        o.addProperty("gap", gap);
        o.addProperty("dotSize", dotSize);
        o.addProperty("circleRadius", circleRadius);
        o.addProperty("circleThickness", circleThickness);
        o.addProperty("rotation", rotation);
        o.addProperty("scale", scale);
        o.addProperty("color", color);
        o.addProperty("separateDotColor", separateDotColor);
        o.addProperty("dotColor", dotColor);
        o.addProperty("chroma", chroma);
        o.addProperty("chromaSpeed", chromaSpeed);
        o.addProperty("invertBlend", invertBlend);
        o.addProperty("outline", outline);
        o.addProperty("outlineThickness", outlineThickness);
        o.addProperty("outlineColor", outlineColor);
        o.addProperty("spreadMoving", spreadMoving);
        o.addProperty("spreadSprinting", spreadSprinting);
        o.addProperty("spreadJumping", spreadJumping);
        o.addProperty("spreadAmount", spreadAmount);
        o.addProperty("recoil", recoil);
        o.addProperty("recoilAmount", recoilAmount);
        o.addProperty("colorOnEntity", colorOnEntity);
        o.addProperty("entityColor", entityColor);
        o.addProperty("colorOnBlock", colorOnBlock);
        o.addProperty("blockColor", blockColor);
        o.addProperty("attackIndicator", attackIndicator);
        o.addProperty("hideInMenus", hideInMenus);
        o.addProperty("showInThirdPerson", showInThirdPerson);
        return o;
    }

    /** Reads every field present in {@code o} through its setter (so values are clamped); missing keys keep theirs. */
    public void applyJson(JsonObject o, boolean includeEnabled) {
        if (includeEnabled) {
            enabled = ConfigJson.getBool(o, "enabled", enabled);
        }
        setStyle(ConfigJson.getEnum(o, "style", Style.class, style));
        setSizeMode(ConfigJson.getEnum(o, "sizeMode", SizeMode.class, sizeMode));
        dot = ConfigJson.getBool(o, "dot", dot);
        armTop = ConfigJson.getBool(o, "armTop", armTop);
        armBottom = ConfigJson.getBool(o, "armBottom", armBottom);
        armLeft = ConfigJson.getBool(o, "armLeft", armLeft);
        armRight = ConfigJson.getBool(o, "armRight", armRight);
        setLength(ConfigJson.getFloat(o, "length", length));
        setThickness(ConfigJson.getFloat(o, "thickness", thickness));
        setGap(ConfigJson.getFloat(o, "gap", gap));
        setDotSize(ConfigJson.getFloat(o, "dotSize", dotSize));
        setCircleRadius(ConfigJson.getFloat(o, "circleRadius", circleRadius));
        setCircleThickness(ConfigJson.getFloat(o, "circleThickness", circleThickness));
        setRotation(ConfigJson.getFloat(o, "rotation", rotation));
        setScale(ConfigJson.getFloat(o, "scale", scale));
        color = ConfigJson.getInt(o, "color", color);
        separateDotColor = ConfigJson.getBool(o, "separateDotColor", separateDotColor);
        dotColor = ConfigJson.getInt(o, "dotColor", dotColor);
        chroma = ConfigJson.getBool(o, "chroma", chroma);
        setChromaSpeed(ConfigJson.getFloat(o, "chromaSpeed", chromaSpeed));
        invertBlend = ConfigJson.getBool(o, "invertBlend", invertBlend);
        outline = ConfigJson.getBool(o, "outline", outline);
        setOutlineThickness(ConfigJson.getFloat(o, "outlineThickness", outlineThickness));
        outlineColor = ConfigJson.getInt(o, "outlineColor", outlineColor);
        spreadMoving = ConfigJson.getBool(o, "spreadMoving", spreadMoving);
        spreadSprinting = ConfigJson.getBool(o, "spreadSprinting", spreadSprinting);
        spreadJumping = ConfigJson.getBool(o, "spreadJumping", spreadJumping);
        setSpreadAmount(ConfigJson.getFloat(o, "spreadAmount", spreadAmount));
        recoil = ConfigJson.getBool(o, "recoil", recoil);
        setRecoilAmount(ConfigJson.getFloat(o, "recoilAmount", recoilAmount));
        colorOnEntity = ConfigJson.getBool(o, "colorOnEntity", colorOnEntity);
        entityColor = ConfigJson.getInt(o, "entityColor", entityColor);
        colorOnBlock = ConfigJson.getBool(o, "colorOnBlock", colorOnBlock);
        blockColor = ConfigJson.getInt(o, "blockColor", blockColor);
        attackIndicator = ConfigJson.getBool(o, "attackIndicator", attackIndicator);
        hideInMenus = ConfigJson.getBool(o, "hideInMenus", hideInMenus);
        showInThirdPerson = ConfigJson.getBool(o, "showInThirdPerson", showInThirdPerson);
    }

    public static void load() {
        CustomCrosshairConfig cfg = new CustomCrosshairConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.applyJson(obj, true);
            } catch (Exception e) {
                cfg = new CustomCrosshairConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(toJson(true)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- built-in presets --------------------------------------------------------------------------------------------

    /** Built-in preset names, in menu order. */
    public static final List<String> BUILT_IN = List.of("Classic", "Vanilla", "Green Pro", "Dot", "Circle Dot",
            "T-Shape", "X", "Valorant");

    /** Puts every appearance/behaviour field back to its default, keeping {@code enabled}. */
    public void resetLook() {
        applyJson(new CustomCrosshairConfig().toJson(false), false);
    }

    /** Applies built-in preset {@code name} (from {@link #BUILT_IN}) over the defaults. False if there is none. */
    public boolean applyBuiltIn(String name) {
        if (!BUILT_IN.contains(name)) {
            return false;
        }
        resetLook();
        switch (name) {
            case "Vanilla" -> {
                // Vanilla's 15x15 cross, one GUI pixel thick, colour-inverted against the world.
                length = 7f;
                gap = 0f;
                dot = true;
                outline = false;
                invertBlend = true;
            }
            case "Green Pro" -> {
                color = 0xFF00FF00;
                length = 3f;
                gap = 1f;
                thickness = 1f;
                sizeMode = SizeMode.GUI;
                outline = true;
            }
            case "Dot" -> {
                style = Style.DOT;
                dotSize = 2f;
                color = 0xFFFF3030;
            }
            case "Circle Dot" -> {
                style = Style.CIRCLE;
                dot = true;
                circleRadius = 5f;
                color = 0xFF00FFFF;
            }
            case "T-Shape" -> {
                style = Style.T_SHAPE;
                color = 0xFFFFFF55;
                length = 5f;
            }
            case "X" -> {
                style = Style.X;
                length = 4f;
                gap = 1.5f;
            }
            case "Valorant" -> {
                color = 0xFF00FFFF;
                length = 2.5f;
                gap = 1.5f;
                dot = true;
                outlineColor = 0xC0000000;
            }
            default -> {
                // Classic: the defaults - a white cross with a black outline.
            }
        }
        return true;
    }

    // ---- saved presets -------------------------------------------------------------------------------------------

    /** A preset name as a file name: letters, digits, space, dash, underscore; at most 32 characters. */
    public static String sanitizePresetName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("[^A-Za-z0-9 _-]", "");
        if (s.length() > 32) {
            s = s.substring(0, 32).trim();
        }
        return s;
    }

    public static List<String> listPresets() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(PRESETS_DIR)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PRESETS_DIR, "*.json")) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - ".json".length()));
            }
        } catch (Exception ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Saves the current look as preset {@code rawName}; returns the name used, or null if the name or write failed. */
    public String savePreset(String rawName) {
        String name = sanitizePresetName(rawName);
        if (name.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(PRESETS_DIR);
            Files.writeString(PRESETS_DIR.resolve(name + ".json"), GSON.toJson(toJson(false)), StandardCharsets.UTF_8);
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean loadPreset(String rawName) {
        String name = sanitizePresetName(rawName);
        Path p = PRESETS_DIR.resolve(name + ".json");
        if (name.isEmpty() || !Files.isRegularFile(p)) {
            return false;
        }
        try {
            applyJson(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject(), false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deletePreset(String rawName) {
        String name = sanitizePresetName(rawName);
        if (name.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(PRESETS_DIR.resolve(name + ".json"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- share code ----------------------------------------------------------------------------------------------

    private static final int F_DOT = 1, F_TOP = 1 << 1, F_BOTTOM = 1 << 2, F_LEFT = 1 << 3, F_RIGHT = 1 << 4,
            F_SEP_DOT = 1 << 5, F_CHROMA = 1 << 6, F_INVERT = 1 << 7, F_OUTLINE = 1 << 8, F_MOVE = 1 << 9,
            F_SPRINT = 1 << 10, F_JUMP = 1 << 11, F_RECOIL = 1 << 12, F_ENTITY = 1 << 13, F_BLOCK = 1 << 14,
            F_ATTACK = 1 << 15, F_HIDE_MENUS = 1 << 16, F_THIRD = 1 << 17;

    /**
     * The look as a short code: {@link #CODE_PREFIX} + URL-safe base64 of a fixed byte layout (style, size mode, a flag
     * word, twelve sizes in 1/20ths as shorts, five ARGB colours) - 50 bytes, 67 characters. Append-only: a later
     * version adds bytes at the end, and an older code simply has fewer.
     */
    public String toShareCode() {
        ByteBuffer b = ByteBuffer.allocate(50);
        b.put((byte) style.ordinal());
        b.put((byte) sizeMode.ordinal());
        int f = 0;
        f |= dot ? F_DOT : 0;
        f |= armTop ? F_TOP : 0;
        f |= armBottom ? F_BOTTOM : 0;
        f |= armLeft ? F_LEFT : 0;
        f |= armRight ? F_RIGHT : 0;
        f |= separateDotColor ? F_SEP_DOT : 0;
        f |= chroma ? F_CHROMA : 0;
        f |= invertBlend ? F_INVERT : 0;
        f |= outline ? F_OUTLINE : 0;
        f |= spreadMoving ? F_MOVE : 0;
        f |= spreadSprinting ? F_SPRINT : 0;
        f |= spreadJumping ? F_JUMP : 0;
        f |= recoil ? F_RECOIL : 0;
        f |= colorOnEntity ? F_ENTITY : 0;
        f |= colorOnBlock ? F_BLOCK : 0;
        f |= attackIndicator ? F_ATTACK : 0;
        f |= hideInMenus ? F_HIDE_MENUS : 0;
        f |= showInThirdPerson ? F_THIRD : 0;
        b.putInt(f);
        for (float v : new float[]{length, thickness, gap, dotSize, circleRadius, circleThickness, rotation, scale,
                outlineThickness, chromaSpeed, spreadAmount, recoilAmount}) {
            b.putShort((short) Math.round(v * 20f));
        }
        b.putInt(color);
        b.putInt(outlineColor);
        b.putInt(dotColor);
        b.putInt(entityColor);
        b.putInt(blockColor);
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    /** Applies a share code over the current look (enabled untouched). False, with nothing changed, if it is not one. */
    public boolean applyShareCode(String code) {
        if (code == null) {
            return false;
        }
        String s = code.trim();
        if (!s.regionMatches(true, 0, CODE_PREFIX, 0, CODE_PREFIX.length())) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(s.substring(CODE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (bytes.length < 50) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        Style[] styles = Style.values();
        SizeMode[] modes = SizeMode.values();
        int st = b.get() & 0xFF;
        int sm = b.get() & 0xFF;
        int f = b.getInt();
        float[] v = new float[12];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getShort() / 20f;
        }
        int[] c = new int[5];
        for (int i = 0; i < c.length; i++) {
            c[i] = b.getInt();
        }
        setStyle(st < styles.length ? styles[st] : Style.CROSS);
        setSizeMode(sm < modes.length ? modes[sm] : SizeMode.GUI);
        dot = (f & F_DOT) != 0;
        armTop = (f & F_TOP) != 0;
        armBottom = (f & F_BOTTOM) != 0;
        armLeft = (f & F_LEFT) != 0;
        armRight = (f & F_RIGHT) != 0;
        separateDotColor = (f & F_SEP_DOT) != 0;
        chroma = (f & F_CHROMA) != 0;
        invertBlend = (f & F_INVERT) != 0;
        outline = (f & F_OUTLINE) != 0;
        spreadMoving = (f & F_MOVE) != 0;
        spreadSprinting = (f & F_SPRINT) != 0;
        spreadJumping = (f & F_JUMP) != 0;
        recoil = (f & F_RECOIL) != 0;
        colorOnEntity = (f & F_ENTITY) != 0;
        colorOnBlock = (f & F_BLOCK) != 0;
        attackIndicator = (f & F_ATTACK) != 0;
        hideInMenus = (f & F_HIDE_MENUS) != 0;
        showInThirdPerson = (f & F_THIRD) != 0;
        setLength(v[0]);
        setThickness(v[1]);
        setGap(v[2]);
        setDotSize(v[3]);
        setCircleRadius(v[4]);
        setCircleThickness(v[5]);
        setRotation(v[6]);
        setScale(v[7]);
        setOutlineThickness(v[8]);
        setChromaSpeed(v[9]);
        setSpreadAmount(v[10]);
        setRecoilAmount(v[11]);
        color = c[0];
        outlineColor = c[1];
        dotColor = c[2];
        entityColor = c[3];
        blockColor = c[4];
        return true;
    }

    /** "3.5" / "4" - a size for a label. */
    public static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.US, "%.2f", v)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
