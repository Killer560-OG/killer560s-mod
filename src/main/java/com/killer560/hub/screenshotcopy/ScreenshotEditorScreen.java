package com.killer560.hub.screenshotcopy;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.gifplayer.GifTextureUtil;
import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenTip;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.screenshotcopy.ScreenshotEdit.Kind;
import com.killer560.hub.screenshotcopy.ScreenshotEdit.Op;
import com.killer560.hub.screenshotcopy.ScreenshotEdit.Picture;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The Screenshot Editor (opened by a click on a screenshot chat line, {@link ScreenshotCopyFeature}). The picture is read
 *  off the render thread and shown as a texture that is released on close. Every edit is an {@link Op} in a list (Undo and
 *  Redo move ops between two lists, at most {@link #MAX_OPS} kept); the shown picture is the original with the ops drawn on
 *  by {@link ScreenshotEdit#render} on a worker, and Copy / Save / Save As Copy run the same render at full size, so the
 *  file is exactly what was shown, never the screen-scaled view. Crop is the newest CROP op: it changes what is shown and
 *  written, other ops keep their picture coordinates. */
public class ScreenshotEditorScreen extends Screen {

    private static final Logger LOGGER = ModLog.get("killer560smod-screenshotcopy");
    private static final int MAX_OPS = 200;
    private static final AtomicInteger SERIAL = new AtomicInteger();
    /** Editor textures alive right now (the testkit checks a close releases its own). */
    public static final AtomicInteger LIVE_TEXTURES = new AtomicInteger();
    private static final long TIP_DELAY_MS = 350L;

    private enum Out { COPY, SAVE, SAVE_COPY }

    private final Path file;
    private volatile Picture src;
    private volatile String loadError;
    private boolean started;
    private volatile boolean closed;

    private final List<Op> ops = new ArrayList<>();
    private final List<Op> redo = new ArrayList<>();
    private int[] appliedCrop;
    private int version;
    private int savedVersion;
    private boolean confirmClose;

    private DynamicTexture tex;
    private Identifier texId;
    private ExecutorService renderer;
    private volatile int renderGen;
    private int shownGen;
    private volatile boolean busy;
    private volatile Path lastSaved;

    private Kind tool = Kind.PEN;
    private int colorIdx;
    private int size = 1;
    private final int[] colors = new int[7];
    private float zoom = 1f;
    private double panX;
    private double panY;
    private boolean panning;
    private int vx;
    private int vy;
    private int vw;
    private int vh;
    private int statusY;

    private int[] draft = new int[16];
    private int draftN;
    private int[] crop;
    private int cropDrag = -1;
    private int cropGrabX;
    private int cropGrabY;
    private CommitEditBox textBox;
    private int textX;
    private int textY;

    private final Map<AbstractWidget, String> tips = new LinkedHashMap<>();
    private final Map<Kind, AbstractWidget> toolButtons = new java.util.EnumMap<>(Kind.class);
    private final AbstractWidget[] sizeButtons = new AbstractWidget[3];
    private final AbstractWidget[] colorButtons = new AbstractWidget[7];
    private AbstractWidget tipWidget;
    private long tipSince;
    private String status = "";
    private long statusUntil;

    public ScreenshotEditorScreen(Path file) {
        super(Component.literal("Screenshot Editor"));
        this.file = file;
    }

    // ---- state the testkit reads and drives ------------------------------------------------------------------------------

    public boolean isLoaded() {
        return src != null;
    }

    public int imageWidth() {
        return src == null ? 0 : src.w();
    }

    public int imageHeight() {
        return src == null ? 0 : src.h();
    }

    public int opCount() {
        return ops.size();
    }

    public int redoCount() {
        return redo.size();
    }

    public boolean isDirty() {
        return version != savedVersion;
    }

    /** True while an Out is being written or the preview is behind the newest edit. */
    public boolean isBusy() {
        return busy || shownGen != renderGen;
    }

    public Path lastSaved() {
        return lastSaved;
    }

    public boolean textureLive() {
        return tex != null;
    }

    public String statusText() {
        return status;
    }

    /** Adds an edit (what a finished drag or a typed text does). */
    public void addOp(Op op) {
        if (src == null || ops.size() >= MAX_OPS) {
            say(ops.size() >= MAX_OPS ? "Edit limit reached: save, then keep editing" : "Still loading");
            return;
        }
        ops.add(op);
        redo.clear();
        changed(op.kind() != Kind.CROP);
    }

    public void undo() {
        if (!ops.isEmpty()) {
            redo.add(ops.remove(ops.size() - 1));
            changed(true);
        }
    }

    public void redo() {
        if (!redo.isEmpty()) {
            ops.add(redo.remove(redo.size() - 1));
            changed(true);
        }
    }

    public void saveOver() {
        output(Out.SAVE);
    }

    public void saveAsCopy() {
        output(Out.SAVE_COPY);
    }

    public void copyToClipboard() {
        output(Out.COPY);
    }

    /** Screen position of a picture point in the current view (for the testkit's real mouse events). */
    public double[] toScreen(double ix, double iy) {
        double s = scale();
        int[] r = region();
        return new double[]{vx + vw / 2.0 + panX + s * (ix - (r[0] + r[2] / 2.0)), vy + vh / 2.0 + panY + s * (iy - (r[1] + r[3] / 2.0))};
    }

    public Kind tool() {
        return tool;
    }

    public void selectTool(Kind k) {
        if (k == Kind.CROP && appliedCrop != null) {
            crop = new int[]{appliedCrop[0], appliedCrop[1], appliedCrop[0] + appliedCrop[2], appliedCrop[1] + appliedCrop[3]};
        } else if (k == Kind.CROP) {
            crop = null;
        }
        if (k != tool) {
            panX = 0;
            panY = 0;
        }
        commitText();
        tool = k;
    }

    // ---- loading, rendering ----------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        MenuStyle.use();
        colors[0] = MenuStyle.OR;
        int[] fixed = {0xFFE53935, 0xFFFFD83A, 0xFF3FBF5F, 0xFF3C7DE8, 0xFFFFFFFF, 0xFF000000};
        System.arraycopy(fixed, 0, colors, 1, fixed.length);
        tips.clear();
        toolButtons.clear();
        List<AbstractWidget> top = new ArrayList<>();
        tool(top, Kind.CROP, "Crop", "Drag a rectangle over the part to keep, adjust it by its handles, then press Apply Crop.");
        tool(top, Kind.PEN, "Pen", "Draw freehand.");
        tool(top, Kind.LINE, "Line", "Drag from one point to another for a straight line.");
        tool(top, Kind.ARROW, "Arrow", "Drag from the tail to where the arrow points.");
        tool(top, Kind.RECT, "Box", "Drag a rectangle outline.");
        tool(top, Kind.MARKER, "Marker", "Drag a see-through highlight over text or an area.");
        tool(top, Kind.TEXT, "Text", "Click where the text goes, type it and press Enter. The size buttons set how big.");
        tool(top, Kind.PIXELATE, "Pixelate", "Drag over names or chat to hide them behind blocks. The size buttons set how coarse.");
        String[] sizeNames = {"Thin", "Medium", "Thick"};
        for (int i = 0; i < 3; i++) {
            int n = i;
            sizeButtons[i] = button(top, sizeNames[i], b -> size = n, "Line width, text size and pixelate block size: " + sizeNames[i].toLowerCase() + ".");
        }
        button(top, "Undo", b -> undo(), "Takes back the last edit. Ctrl+Z does the same.");
        button(top, "Redo", b -> redo(), "Puts back the edit you took back. Ctrl+Y does the same.");
        button(top, "Apply Crop", b -> applyCrop(false), "Keeps only the rectangle you dragged. Undo takes it back.");
        button(top, "Reset Crop", b -> applyCrop(true), "Shows and saves the whole picture again.");
        int topBottom = place(top, 4, true) ;

        List<AbstractWidget> bottom = new ArrayList<>();
        String[] colorNames = {"menu color", "red", "yellow", "green", "blue", "white", "black"};
        for (int i = 0; i < 7; i++) {
            int n = i;
            AbstractWidget w = SettingsButtonWidget.builder(Component.literal(""), b -> colorIdx = n).bounds(0, 0, 16, 16).build();
            colorButtons[i] = w;
            tips.put(w, "Draws in " + colorNames[i] + ".");
            bottom.add(w);
            addRenderableWidget(w);
        }
        button(bottom, "Copy", b -> copyToClipboard(), "Copies the edited picture to the clipboard.");
        button(bottom, "Save", b -> saveOver(), "Saves the edits over this screenshot's file.");
        button(bottom, "Save As Copy", b -> saveAsCopy(), "Saves the edits as a new file beside this one (name-edited.png). The original stays as it is.");
        button(bottom, "Cancel", b -> onClose(), "Closes the editor. Unsaved edits ask once first. Esc does the same.");
        int rows = place(bottom, 0, false);
        int bottomTop = height - 4 - rows;
        place(bottom, bottomTop, true);
        statusY = bottomTop - 12;
        vx = 4;
        vy = topBottom + 2;
        vw = width - 8;
        vh = Math.max(20, statusY - 2 - vy);
        if (!started) {
            started = true;
            Thread t = new Thread(this::load, "killer560smod-screenshot-load");
            t.setDaemon(true);
            t.start();
        }
    }

    private void tool(List<AbstractWidget> row, Kind kind, String label, String tip) {
        toolButtons.put(kind, button(row, label, b -> selectTool(kind), tip));
    }

    private AbstractWidget button(List<AbstractWidget> row, String label, SettingsButtonWidget.OnPress press, String tip) {
        AbstractWidget w = SettingsButtonWidget.builder(Component.literal(label), press)
                .bounds(0, 0, font.width(label) + 12, 16).build();
        tips.put(w, tip);
        row.add(w);
        return addRenderableWidget(w);
    }

    /** Lays {@code row} left to right from y, wrapping at the screen edge; returns the number of pixels used (the bottom y
     *  when {@code absolute}, else the height of the rows). */
    private int place(List<AbstractWidget> row, int y, boolean absolute) {
        int x = 4;
        int rowY = y;
        for (AbstractWidget w : row) {
            if (x > 4 && x + w.getWidth() > width - 4) {
                x = 4;
                rowY += 20;
            }
            w.setX(x);
            w.setY(rowY);
            x += w.getWidth() + 3;
        }
        return absolute ? rowY + 18 : rowY - y + 18;
    }

    private void load() {
        try {
            Picture p = ScreenshotEdit.read(file);
            Minecraft.getInstance().execute(() -> {
                if (!closed) {
                    src = p;
                    requestRender();
                }
            });
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Screenshot editor: could not read {}", file, e);
            loadError = "Could not read " + file.getFileName();
        }
    }

    /** The preview picture is redrawn on a worker; only the newest request is uploaded. */
    private void requestRender() {
        Picture p = src;
        if (p == null || closed) {
            return;
        }
        if (renderer == null) {
            renderer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "killer560smod-screenshot-render");
                t.setDaemon(true);
                return t;
            });
        }
        int gen = ++renderGen;
        List<Op> snap = List.copyOf(ops);
        renderer.execute(() -> {
            if (gen != renderGen) {
                return;
            }
            try {
                NativeImage img = new NativeImage(p.w(), p.h(), false);
                GifTextureUtil.writeFrame(img, ScreenshotEdit.render(p.argb(), p.w(), p.h(), snap));
                Minecraft.getInstance().execute(() -> show(gen, img));
            } catch (RuntimeException e) {
                LOGGER.warn("Screenshot editor: preview failed", e);
            }
        });
    }

    private void show(int gen, NativeImage img) {
        if (closed || gen != renderGen) {
            img.close();
            return;
        }
        if (tex == null) {
            texId = Identifier.fromNamespaceAndPath("killer560smod", "screenshotedit/" + SERIAL.incrementAndGet());
            tex = new DynamicTexture(() -> "killer560smod screenshot editor", img);
            Minecraft.getInstance().getTextureManager().register(texId, tex);
            LIVE_TEXTURES.incrementAndGet();
        } else {
            NativeImage old = tex.getPixels();
            tex.setPixels(img);
            if (old != img) {
                old.close();
            }
            tex.upload();
        }
        shownGen = gen;
    }

    private void changed(boolean render) {
        version++;
        confirmClose = false;
        appliedCrop = ScreenshotEdit.cropOf(ops, imageWidth(), imageHeight());
        if (render) {
            requestRender();
        }
    }

    @Override
    public void removed() {
        closed = true;
        if (renderer != null) {
            renderer.shutdownNow();
        }
        if (tex != null) {
            Minecraft.getInstance().getTextureManager().release(texId);
            tex = null;
            texId = null;
            LIVE_TEXTURES.decrementAndGet();
        }
        super.removed();
    }

    // ---- output ------------------------------------------------------------------------------------------------------------

    private void output(Out kind) {
        Picture p = src;
        if (p == null || busy) {
            return;
        }
        busy = true;
        List<Op> snap = List.copyOf(ops);
        int ver = version;
        say("Working...");
        Thread t = new Thread(() -> {
            String result;
            Path written = null;
            try {
                Picture out = ScreenshotEdit.crop(ScreenshotEdit.render(p.argb(), p.w(), p.h(), snap), p.w(), p.h(),
                        ScreenshotEdit.cropOf(snap, p.w(), p.h()));
                switch (kind) {
                    case SAVE -> {
                        ScreenshotEdit.write(out, file);
                        written = file;
                        result = "Saved " + file.getFileName();
                    }
                    case SAVE_COPY -> {
                        written = ScreenshotEdit.writeCopy(out, file);
                        result = "Saved " + written.getFileName();
                    }
                    default -> {
                        Path tmp = Files.createTempFile("killer560smod-edit-", ".png");
                        try {
                            ScreenshotEdit.write(out, tmp);
                            result = ScreenshotCopyFeature.copyToClipboard(tmp) ? "Copied to the clipboard" : "Could not copy to the clipboard";
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Screenshot editor: {} failed for {}", kind, file, e);
                result = "Failed: " + e.getMessage();
                written = null;
            }
            String text = result;
            Path wrote = written;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (wrote != null) {
                    lastSaved = wrote;
                    savedVersion = ver;
                }
                say(text);
            });
        }, "killer560smod-screenshot-save");
        t.setDaemon(true);
        t.start();
    }

    private void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + 5000L;
    }

    // ---- view math -------------------------------------------------------------------------------------------------------

    /** The part of the picture on screen: all of it while cropping, else the applied crop, else all of it. */
    private int[] region() {
        if (tool != Kind.CROP && appliedCrop != null) {
            return appliedCrop;
        }
        return new int[]{0, 0, Math.max(1, imageWidth()), Math.max(1, imageHeight())};
    }

    private double scale() {
        int[] r = region();
        return Math.min((double) vw / r[2], (double) vh / r[3]) * zoom;
    }

    private double toImageX(double mx) {
        int[] r = region();
        return (mx - vx - vw / 2.0 - panX) / scale() + r[0] + r[2] / 2.0;
    }

    private double toImageY(double my) {
        int[] r = region();
        return (my - vy - vh / 2.0 - panY) / scale() + r[1] + r[3] / 2.0;
    }

    private int px(double mx) {
        return (int) Math.max(0, Math.min(imageWidth(), Math.round(toImageX(mx))));
    }

    private int py(double my) {
        return (int) Math.max(0, Math.min(imageHeight(), Math.round(toImageY(my))));
    }

    private boolean inView(double mx, double my) {
        return mx >= vx && mx < vx + vw && my >= vy && my < vy + vh;
    }

    // ---- drawing -----------------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xE6101010);
        g.fill(vx, vy, vx + vw, vy + vh, MenuStyle.WELL);
        if (src == null || tex == null) {
            String s = loadError != null ? loadError : "Loading...";
            MenuStyle.text(g, s, vx + (vw - font.width(s)) / 2, vy + vh / 2 - 4, loadError != null ? MenuStyle.RED : MenuStyle.DIM);
        } else {
            drawPicture(g);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        for (int i = 0; i < 7; i++) {
            AbstractWidget w = colorButtons[i];
            g.fill(w.getX() + 3, w.getY() + 3, w.getX() + w.getWidth() - 3, w.getY() + w.getHeight() - 3, colors[i]);
        }
        highlight(g, toolButtons.get(tool));
        highlight(g, sizeButtons[size]);
        highlight(g, colorButtons[colorIdx]);
        long now = System.currentTimeMillis();
        String left = file.getFileName() + (src == null ? "" : "  " + imageWidth() + "x" + imageHeight() + "  zoom " + Math.round(zoom * 100) + "%");
        MenuStyle.text(g, left, 4, statusY, MenuStyle.TXT_SOFT);
        if (now < statusUntil) {
            MenuStyle.text(g, status, width - 4 - font.width(status), statusY, MenuStyle.OR_LIGHT);
        }
        drawTip(g, mouseX, mouseY, now);
    }

    private void highlight(GuiGraphicsExtractor g, AbstractWidget w) {
        if (w != null) {
            outline(g, w.getX() - 1, w.getY() - 1, w.getWidth() + 2, w.getHeight() + 2, MenuStyle.OR);
        }
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void drawPicture(GuiGraphicsExtractor g) {
        int[] r = region();
        double s = scale();
        double[] at = toScreen(r[0], r[1]);
        int w = imageWidth();
        int h = imageHeight();
        g.enableScissor(vx, vy, vx + vw, vy + vh);
        GifTextureUtil.blit(g, tex, (int) Math.round(at[0]), (int) Math.round(at[1]), (int) Math.round(r[2] * s), (int) Math.round(r[3] * s),
                r[0] / (float) w, (r[0] + r[2]) / (float) w, r[1] / (float) h, (r[1] + r[3]) / (float) h);
        if (tool == Kind.CROP && crop != null) {
            drawCrop(g);
        } else if (draftN >= 2) {
            drawDraft(g, s);
        }
        g.disableScissor();
    }

    private void drawCrop(GuiGraphicsExtractor g) {
        double[] a = toScreen(crop[0], crop[1]);
        double[] b = toScreen(crop[2], crop[3]);
        int x0 = (int) Math.round(a[0]);
        int y0 = (int) Math.round(a[1]);
        int x1 = (int) Math.round(b[0]);
        int y1 = (int) Math.round(b[1]);
        int dim = 0xA0000000;
        g.fill(vx, vy, vx + vw, y0, dim);
        g.fill(vx, y1, vx + vw, vy + vh, dim);
        g.fill(vx, y0, x0, y1, dim);
        g.fill(x1, y0, vx + vw, y1, dim);
        outline(g, x0, y0, x1 - x0, y1 - y0, MenuStyle.OR);
        for (int i = 0; i < 8; i++) {
            int[] c = handle(i, x0, y0, x1, y1);
            g.fill(c[0] - 3, c[1] - 3, c[0] + 4, c[1] + 4, MenuStyle.OR_LIGHT);
        }
    }

    /** Handle i (0..7: corners clockwise from top-left, then edge midpoints top, right, bottom, left) as a screen point. */
    private static int[] handle(int i, int x0, int y0, int x1, int y1) {
        int mx = (x0 + x1) / 2;
        int my = (y0 + y1) / 2;
        return switch (i) {
            case 0 -> new int[]{x0, y0};
            case 1 -> new int[]{x1, y0};
            case 2 -> new int[]{x1, y1};
            case 3 -> new int[]{x0, y1};
            case 4 -> new int[]{mx, y0};
            case 5 -> new int[]{x1, my};
            case 6 -> new int[]{mx, y1};
            default -> new int[]{x0, my};
        };
    }

    /** The edit being dragged, drawn as plain rectangles over the picture (the real drawing is done by the worker once the
     *  drag ends). */
    private void drawDraft(GuiGraphicsExtractor g, double s) {
        int color = colors[colorIdx];
        int t = Math.max(1, (int) Math.round(ScreenshotEdit.px(size, imageWidth(), imageHeight()) * s));
        if (tool == Kind.PEN) {
            for (int i = 0; i + 1 < draftN; i += 2) {
                double[] p = toScreen(draft[i], draft[i + 1]);
                if (i >= 2) {
                    dots(g, toScreen(draft[i - 2], draft[i - 1]), p, t, color);
                }
                g.fill((int) p[0] - t / 2, (int) p[1] - t / 2, (int) p[0] - t / 2 + t, (int) p[1] - t / 2 + t, color);
            }
            return;
        }
        double[] a = toScreen(draft[0], draft[1]);
        double[] b = toScreen(draft[2], draft[3]);
        switch (tool) {
            case LINE, ARROW -> dots(g, a, b, t, color);
            case MARKER -> g.fill((int) a[0], (int) a[1], (int) b[0], (int) b[1], (color & 0xFFFFFF) | 0x66000000);
            case PIXELATE -> {
                g.fill((int) a[0], (int) a[1], (int) b[0], (int) b[1], 0x55808080);
                outline(g, (int) Math.min(a[0], b[0]), (int) Math.min(a[1], b[1]), (int) Math.abs(b[0] - a[0]), (int) Math.abs(b[1] - a[1]), MenuStyle.OR);
            }
            default -> outline(g, (int) Math.min(a[0], b[0]), (int) Math.min(a[1], b[1]), (int) Math.abs(b[0] - a[0]) + 1, (int) Math.abs(b[1] - a[1]) + 1, color);
        }
    }

    private static void dots(GuiGraphicsExtractor g, double[] a, double[] b, int t, int color) {
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int n = (int) Math.max(1, Math.min(2000, len / Math.max(1, t / 2.0)));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * i / n);
            int y = (int) Math.round(a[1] + (b[1] - a[1]) * i / n);
            g.fill(x - t / 2, y - t / 2, x - t / 2 + t, y - t / 2 + t, color);
        }
    }

    private void drawTip(GuiGraphicsExtractor g, int mx, int my, long now) {
        AbstractWidget hit = null;
        for (AbstractWidget w : tips.keySet()) {
            if (w.visible && mx >= w.getX() && mx < w.getX() + w.getWidth() && my >= w.getY() && my < w.getY() + w.getHeight()) {
                hit = w;
                break;
            }
        }
        if (hit != tipWidget) {
            tipWidget = hit;
            tipSince = now;
        }
        if (hit != null && now - tipSince >= TIP_DELAY_MS) {
            ScreenTip.draw(g, font, tips.get(hit), mx, my, width, height);
        }
    }

    // ---- mouse and keys ----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (textBox != null && !(mx >= textBox.getX() && mx < textBox.getX() + textBox.getWidth() && my >= textBox.getY() && my < textBox.getY() + textBox.getHeight())) {
            commitText();
        }
        if (src != null && inView(mx, my) && !(textBox != null && textBox.isMouseOver(mx, my))) {
            int b = McInput.button(event);
            if (b == 1 || b == 2) {
                panning = true;
                return true;
            }
            if (b == 0) {
                begin(mx, my);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void begin(double mx, double my) {
        int x = px(mx);
        int y = py(my);
        draftN = 0;
        switch (tool) {
            case CROP -> beginCrop(mx, my, x, y);
            case TEXT -> openText(mx, my, x, y);
            case PEN -> push(x, y);
            default -> {
                push(x, y);
                push(x, y);
            }
        }
    }

    private void push(int x, int y) {
        if (draftN + 2 > draft.length) {
            draft = Arrays.copyOf(draft, draft.length * 2);
        }
        draft[draftN++] = x;
        draft[draftN++] = y;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (panning) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        if (cropDrag >= 0 && crop != null) {
            dragCrop(px(event.x()), py(event.y()));
            return true;
        }
        if (draftN >= 2) {
            int x = px(event.x());
            int y = py(event.y());
            if (tool == Kind.PEN) {
                if (x != draft[draftN - 2] || y != draft[draftN - 1]) {
                    push(x, y);
                }
            } else {
                draft[2] = x;
                draft[3] = y;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panning = false;
        if (cropDrag >= 0) {
            cropDrag = -1;
            if (crop != null && (crop[2] - crop[0] < 4 || crop[3] - crop[1] < 4)) {
                crop = null;
            }
        } else if (draftN >= 2) {
            finishDraft();
        }
        return super.mouseReleased(event);
    }

    private void finishDraft() {
        int[] p = Arrays.copyOf(draft, draftN);
        draftN = 0;
        boolean shape = tool != Kind.PEN;
        if (shape && Math.abs(p[2] - p[0]) < 2 && Math.abs(p[3] - p[1]) < 2) {
            return;
        }
        addOp(new Op(tool, colors[colorIdx], size, p, null));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (src != null && inView(mouseX, mouseY) && scrollY != 0) {
            double ix = toImageX(mouseX);
            double iy = toImageY(mouseY);
            zoom = (float) Math.max(0.25, Math.min(24, zoom * Math.pow(1.15, scrollY)));
            int[] r = region();
            double s = scale();
            panX = mouseX - vx - vw / 2.0 - s * (ix - (r[0] + r[2] / 2.0));
            panY = mouseY - vy - vh / 2.0 - s * (iy - (r[1] + r[3] / 2.0));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            if (textBox != null) {
                cancelText();
            } else {
                onClose();
            }
            return true;
        }
        if (textBox == null && event.hasControlDown()) {
            if (event.key() == InputConstants.KEY_Z && !event.hasShiftDown()) {
                undo();
                return true;
            }
            if (event.key() == InputConstants.KEY_Y || event.key() == InputConstants.KEY_Z) {
                redo();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    // ---- crop --------------------------------------------------------------------------------------------------------------

    private void beginCrop(double mx, double my, int x, int y) {
        cropDrag = -1;
        if (crop != null) {
            double[] a = toScreen(crop[0], crop[1]);
            double[] b = toScreen(crop[2], crop[3]);
            for (int i = 0; i < 8; i++) {
                int[] c = handle(i, (int) a[0], (int) a[1], (int) b[0], (int) b[1]);
                if (Math.abs(mx - c[0]) <= 6 && Math.abs(my - c[1]) <= 6) {
                    cropDrag = i;
                    return;
                }
            }
            if (x > crop[0] && x < crop[2] && y > crop[1] && y < crop[3]) {
                cropDrag = 8;
                cropGrabX = x;
                cropGrabY = y;
                return;
            }
        }
        crop = new int[]{x, y, x, y};
        cropDrag = 2;
    }

    private void dragCrop(int x, int y) {
        int w = imageWidth();
        int h = imageHeight();
        if (cropDrag == 8) {
            int dx = Math.max(-crop[0], Math.min(w - crop[2], x - cropGrabX));
            int dy = Math.max(-crop[1], Math.min(h - crop[3], y - cropGrabY));
            crop[0] += dx;
            crop[2] += dx;
            crop[1] += dy;
            crop[3] += dy;
            cropGrabX += dx;
            cropGrabY += dy;
            return;
        }
        // corners 0 TL, 1 TR, 2 BR, 3 BL; edges 4 top, 5 right, 6 bottom, 7 left: the dragged side(s) follow the mouse and
        // may cross the opposite side, which then swaps
        boolean left = cropDrag == 0 || cropDrag == 3 || cropDrag == 7;
        boolean right = cropDrag == 1 || cropDrag == 2 || cropDrag == 5;
        boolean topSide = cropDrag == 0 || cropDrag == 1 || cropDrag == 4;
        boolean bottomSide = cropDrag == 2 || cropDrag == 3 || cropDrag == 6;
        if (left) {
            crop[0] = x;
        }
        if (right) {
            crop[2] = x;
        }
        if (topSide) {
            crop[1] = y;
        }
        if (bottomSide) {
            crop[3] = y;
        }
        if (crop[0] > crop[2]) {
            int t = crop[0];
            crop[0] = crop[2];
            crop[2] = t;
            cropDrag = swapX(cropDrag);
        }
        if (crop[1] > crop[3]) {
            int t = crop[1];
            crop[1] = crop[3];
            crop[3] = t;
            cropDrag = swapY(cropDrag);
        }
    }

    private static int swapX(int h) {
        return switch (h) {
            case 0 -> 1;
            case 1 -> 0;
            case 2 -> 3;
            case 3 -> 2;
            case 5 -> 7;
            case 7 -> 5;
            default -> h;
        };
    }

    private static int swapY(int h) {
        return switch (h) {
            case 0 -> 3;
            case 3 -> 0;
            case 1 -> 2;
            case 2 -> 1;
            case 4 -> 6;
            case 6 -> 4;
            default -> h;
        };
    }

    /** Apply Crop (or Reset Crop: the whole picture again) as one undoable edit. */
    public void applyCrop(boolean reset) {
        if (reset) {
            crop = null;
            addOp(new Op(Kind.CROP, 0, 0, new int[0], null));
            return;
        }
        if (crop == null || crop[2] - crop[0] < 4 || crop[3] - crop[1] < 4) {
            say("Drag a rectangle with the Crop tool first");
            return;
        }
        addOp(new Op(Kind.CROP, 0, 0, crop.clone(), null));
    }

    // ---- text --------------------------------------------------------------------------------------------------------------

    private void openText(double mx, double my, int x, int y) {
        commitText();
        textX = x;
        textY = y;
        int bx = (int) Math.min(mx, vx + vw - 120);
        textBox = new CommitEditBox(font, bx, (int) my, 120, 16, Component.literal("Text"));
        textBox.setMaxLength(80);
        textBox.onEnter(this::commitText);
        addRenderableWidget(textBox);
        setFocused(textBox);
        textBox.setFocused(true);
    }

    private void commitText() {
        if (textBox == null) {
            return;
        }
        String t = textBox.getValue().trim();
        CommitEditBox box = textBox;
        textBox = null;
        removeWidget(box);
        if (!t.isEmpty()) {
            addOp(new Op(Kind.TEXT, colors[colorIdx], size, new int[]{textX, textY}, t));
        }
    }

    private void cancelText() {
        removeWidget(textBox);
        textBox = null;
    }

    // ---- closing -----------------------------------------------------------------------------------------------------------

    @Override
    public void onClose() {
        if (isDirty() && !confirmClose) {
            confirmClose = true;
            say("Unsaved edits. Press Esc or Cancel again to throw them away, or Save first.");
            return;
        }
        closeNow();
    }

    public void closeNow() {
        McCompat.setScreen(minecraft, null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
