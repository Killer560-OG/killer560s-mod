package com.killer560.hub.gui.tab;

import com.killer560.hub.commandkeybinds.CommandKeybindsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat Keybinds settings - see {@link com.killer560.hub.commandkeybinds.CommandKeybindsFeature}'s class doc.
 *  One row per bind: the key (or mouse button) and the exact line it sends. Renamed from "Command Keybinds"
 *  on 2026-09-20 when killer560 asked to type his own command/message per bind; the old 8 fixed menu binds
 *  are migrated into rows by {@link CommandKeybindsConfig}, so existing keys keep working. */
public class CommandKeybindsTab extends BaseTab implements KeyCaptureTab {

    private CommandKeybindsConfig.Bind capturing = null;

    public CommandKeybindsTab() {
        super("Chat Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
    }

    /** Lets {@code ModScreen} route the next mouse press here instead of to the widget under the cursor.
     *  Not yet an interface method - the {@code KeyCaptureTab}/{@code ModScreen} patch is in this wave's
     *  staging notes; it becomes an override the moment that lands. */
    public boolean supportsMouseCapture() {
        return true;
    }

    /** Mouse half of the capture (killer560: "make all of the keybind things compatible with mouse buttons
     *  and middle mouse buttons"). Not yet an interface method - {@code ModScreen}'s routing patch is in this
     *  wave's staging notes; until it lands this simply never gets called. */
    public void onMouseCaptured(int button) {
        applyCapture(CommandKeybindsConfig.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        if (capturing == null) {
            return;
        }
        capturing.key = code;
        capturing = null;
        CommandKeybindsConfig.getInstance().save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int gap = 8;
        int half = (contentWidth - gap) / 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Chat Keybinds", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Bind"), btn -> {
                    cfg.addBind();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half, 18).build());
        y += 24;

        int keyW = 90;
        int deleteW = 50;
        int textW = contentWidth - keyW - deleteW - gap * 2;
        for (CommandKeybindsConfig.Bind bind : new ArrayList<>(cfg.binds())) {
            int x = contentX;
            widgets.add(SettingsButtonWidget.builder(keyText(bind), btn -> {
                        capturing = bind;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(x, y, keyW, 18).build());
            x += keyW + gap;

            EditBox textField = new EditBox(font, x, y, textW, 18, Component.literal("Command or message"));
            textField.setMaxLength(200);
            textField.setValue(bind.text);
            textField.setHint(Component.literal("§8/pets  or  gg"));
            textField.setResponder(text -> {
                bind.text = text;
                cfg.save();
            });
            widgets.add(textField);
            x += textW + gap;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        cfg.removeBind(bind);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x, y, deleteW, 18).build());
            y += 22;
        }

        if (cfg.binds().isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No binds yet - hit \"+ Add Bind\"."), font));
        }

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private Component keyText(CommandKeybindsConfig.Bind bind) {
        if (capturing == bind) {
            return Component.literal("Press any key...");
        }
        return Component.literal("Key: " + bindName(bind.key));
    }

    /** Display name for a keyboard code or a stored mouse code (see {@code CommandKeybindsConfig}). */
    static String bindName(int code) {
        if (code == -1) {
            return "Not Set";
        }
        return CommandKeybindsConfig.isMouseCode(code)
                ? InputConstants.Type.MOUSE.getOrCreate(CommandKeybindsConfig.mouseButton(code))
                        .getDisplayName().getString()
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
    }
}
