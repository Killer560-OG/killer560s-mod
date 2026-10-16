package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.gui.MenuAnim;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.social.PlayerHeadRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * One account on the Swap Accounts screen (killer560, 2026-10-15: "please redo how this looks"): a card across the
 * list's whole width with the account's skin head, its name, a solid accent "ACTIVE" pill on the account the game is signed
 * in as (whose whole card takes the accent since 2026-10-16),
 * the proxy it connects through (none / its own / the Universal one) under the name, and its Hypixel ban status on the
 * right. A press anywhere on the card signs in as the account; its Copy ID and proxy buttons are separate widgets the
 * screen lays over the card's right end ({@link #textRight} is where they begin).
 *
 * <p>The label is the account's name, so the row is found by it (narration, the testkit). The screen moves the row
 * every frame (smooth scrolling) and draws it under the list's scissor itself.
 */
final class AccountRowWidget extends net.minecraft.client.gui.components.AbstractWidget {

    static final int HEAD = 20;
    /** The signed-in account's tag (the testkit reads it, case-insensitively). */
    static final String PILL = "ACTIVE";

    final PrismAccount account;
    final boolean signedIn;
    private final UUID id;
    private final Consumer<PrismAccount> onPress;

    /** 0 no proxy, 1 its own, 2 the Universal proxy (set by the screen each tick). */
    int proxyKind;
    /** The account's own proxy type ("SOCKS5"), when it has one. */
    String ownType;
    /** Ban status lines (null = none recorded) and whether it is a ban. */
    String ban1;
    String ban2;
    boolean banned;
    /** Where the row's buttons begin: text stops short of it. */
    int textRight;

    final MenuAnim.Tween hover = new MenuAnim.Tween(MenuAnim.HOVER_MS, false);

    AccountRowWidget(PrismAccount account, boolean signedIn, Consumer<PrismAccount> onPress) {
        super(0, 0, 100, 28, Component.literal(account.displayName()));
        this.account = account;
        this.signedIn = signedIn;
        this.id = parseUuid(account.uuid());
        this.onPress = onPress;
    }

    /** A Prism profile id: 32 hex digits without dashes (or a dashed UUID); null when it is neither. */
    static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.length() == 32) {
                s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
                        + "-" + s.substring(20);
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    UUID id() {
        return id;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (onPress != null) {
            onPress.accept(account);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        hover.set(isHovered() && active ? 1f : 0f);
        float hv = hover.get();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        GuiRects r = GuiRects.begin(g);
        if (signedIn) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x58 + Math.round(0x20 * hv));
        }
        MenuStyle.dropShadow(r, x, y, w, h, 2);
        int top = MenuStyle.ease(MenuStyle.CARD_TOP, MenuStyle.CARD_HOVER_TOP, hv);
        int bottom = MenuStyle.ease(MenuStyle.CARD_BOTTOM, MenuStyle.CARD_HOVER_BOTTOM, hv);
        if (signedIn) {
            // The signed-in account stands out (killer560, 2026-10-16: "Make Active a bit more noticeable"): the card
            // itself takes the theme's accent, a two-pixel accent frame, a full-height accent bar, the name in the
            // accent and a solid ACTIVE pill - where it had a thin outline and a small green tag.
            top = MenuStyle.mix(MenuStyle.CARD_TOP, MenuStyle.OR, 0.30f + 0.06f * hv);
            bottom = MenuStyle.mix(MenuStyle.CARD_BOTTOM, MenuStyle.OR, 0.18f + 0.06f * hv);
        }
        MenuStyle.bevel(r, x, y, w, h, top, bottom);
        if (signedIn) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.OR);
            r.outline(x - 2, y - 2, w + 4, h + 4, MenuStyle.alpha(MenuStyle.OR, 0xA0));
        } else if (hv > 0f) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.alpha(MenuStyle.ACCENT_EDGE, Math.round(0xFF * hv)));
        }
        // the accent bar down the left edge: always on (and wider) on the signed-in account, growing in on hover elsewhere
        if (signedIn) {
            r.fill(x, y, x + 4, y + h, MenuStyle.OR);
        } else if (hv > 0f) {
            int barH = Math.max(1, Math.round((h - 4) * hv));
            int barY = y + 2 + (h - 4 - barH) / 2;
            r.fill(x + 1, barY, x + 3, barY + barH, MenuStyle.OR2);
        }
        // the head's frame
        int hx = x + 7;
        int hy = y + (h - HEAD) / 2;
        r.fill(hx - 1, hy - 1, hx + HEAD + 1, hy + HEAD + 1, MenuStyle.alpha(MenuStyle.BLACK, 0xC0));
        r.submit();
        if (MenuAnim.fade >= 0.5f) {
            PlayerHeadRenderer.draw(g, id, account.displayName(), hx, hy, HEAD);
        }

        int nameX = hx + HEAD + 8;
        int right = textRight > 0 ? textRight - 6 : x + w - 6;
        int line1 = y + h / 2 - 9;
        int line2 = y + h / 2 + 2;

        // ban status, right-aligned against the buttons; it gives way to the name first
        var font = MenuStyle.font();
        int room = right - nameX;
        int banW = 0;
        String b1 = null;
        String b2 = null;
        if (ban1 != null && room > 120) {
            int max = Math.min(Math.max(0, room - 110), Math.max(font.width(ban1), ban2 == null ? 0 : font.width(ban2)));
            if (max >= 40) {
                b1 = MenuStyle.ellipsize(ban1, max);
                b2 = ban2 == null ? null : MenuStyle.ellipsize(ban2, max);
                banW = Math.max(font.width(b1), b2 == null ? 0 : font.width(b2));
            }
        }
        if (b1 != null) {
            ScreenChrome.text(g, b1, right - font.width(b1), line1, banned ? MenuStyle.RED : ScreenChrome.good());
            if (b2 != null) {
                ScreenChrome.text(g, b2, right - font.width(b2), line2, MenuStyle.DIM);
            }
        }
        int nameRoom = right - nameX - (banW > 0 ? banW + 10 : 0);

        String pill = signedIn ? PILL : null;
        int pillW = pill == null ? 0 : font.width(pill) + 10;
        String name = MenuStyle.ellipsize(account.displayName(), Math.max(10, nameRoom - (pill == null ? 0 : pillW + 6)));
        int nameColour = signedIn ? ScreenChrome.readable(MenuStyle.OR_LIGHT)
                : hv > 0.5f ? MenuStyle.TXT_HOVER : MenuStyle.TXT;
        ScreenChrome.text(g, name, nameX, line1, nameColour);
        if (pill != null) {
            // A solid accent pill with text in whichever of near-black or white reads on that accent.
            int px = nameX + font.width(name) + 6;
            // On Light every text is dark (testkit's Light sweep), so the pill is a pale accent there instead.
            int fill = MenuStyle.LIGHT ? MenuStyle.mix(MenuStyle.OR, 0xFFFFFFFF, 0.55f) : MenuStyle.OR;
            int ink = MenuStyle.luminance(fill) > 0.45f ? 0xFF140C04 : 0xFFFFFFFF;
            GuiRects pr = GuiRects.begin(g);
            pr.fill(px, line1 - 2, px + pillW, line1 + 9, fill);
            pr.fill(px + 1, line1 - 2, px + pillW - 1, line1 - 1, MenuStyle.alpha(0xFFFFFFFF, 0x50));
            pr.outline(px - 1, line1 - 3, pillW + 2, 13, MenuStyle.alpha(MenuStyle.BLACK, 0x90));
            pr.submit();
            MenuStyle.flat(g, pill, px + 5, line1, ink);
        }

        // proxy line: a status dot and what this account connects through
        int dotColour;
        String proxy;
        String extra = null;
        if (proxyKind == 2) {
            dotColour = ScreenChrome.readable(0xFF5AB4FF);
            proxy = "Universal proxy";
            if (ownType != null) {
                extra = "own proxy saved";
            }
        } else if (proxyKind == 1) {
            dotColour = MenuStyle.OR;
            proxy = "Own proxy" + (ownType != null ? " (" + ownType + ")" : "");
        } else {
            dotColour = MenuStyle.DIM2;
            proxy = "No proxy";
        }
        GuiRects dr = GuiRects.begin(g);
        dr.fill(nameX, line2 + 2, nameX + 4, line2 + 6, dotColour);
        dr.submit();
        String p = MenuStyle.ellipsize(proxy, Math.max(10, nameRoom - 8));
        ScreenChrome.text(g, p, nameX + 8, line2, proxyKind == 0 ? MenuStyle.DIM : dotColour);
        if (extra != null) {
            int ex = nameX + 8 + font.width(p) + 6;
            String e = MenuStyle.ellipsize(extra, nameX + nameRoom - ex);
            if (ex + 20 < nameX + nameRoom) {
                ScreenChrome.text(g, e, ex, line2, MenuStyle.DIM2);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
