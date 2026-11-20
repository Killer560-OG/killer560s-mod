package com.killer560.hub.sharesite;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Share To Website's settings (sharesite-1119, killer560 2026-11-19: ON by default, offered in the first-launch tour):
 * the switch, and {@code pendingDelete}, set when it was turned off and cleared once the relay confirmed the delete. A
 * profile someone else made never changes either (ProfileManager's import-locked fields).
 */
public final class ShareToWebsiteConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-sharesite.json");
    private static final BoolFields<ShareToWebsiteConfig> FIELDS = new BoolFields<ShareToWebsiteConfig>()
            .add("enabled", true, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("pendingDelete", false, c -> c.pendingDelete, (c, v) -> c.pendingDelete = v)
            .add("noticeShown", false, c -> c.noticeShown, (c, v) -> c.noticeShown = v);

    private static ShareToWebsiteConfig instance;

    private boolean enabled = true;
    private boolean pendingDelete;
    /** The intro's Share To Website scene was shown and answered: until then nothing is sent, whatever the switch says. */
    private boolean noticeShown;

    private ShareToWebsiteConfig() {
    }

    public static synchronized ShareToWebsiteConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Also run by a profile apply: one that turns sharing off asks the relay to delete, like the switch does. */
    public static void load() {
        boolean turnedOff;
        synchronized (ShareToWebsiteConfig.class) {
            boolean wasOn = instance != null && instance.enabled;
            ShareToWebsiteConfig cfg = new ShareToWebsiteConfig();
            FIELDS.load(cfg, ConfigJson.read(CONFIG_PATH));
            instance = cfg;
            turnedOff = wasOn && !cfg.enabled;
        }
        // Outside this class's lock: ShareToWebsite takes its own lock first and this one inside it.
        if (turnedOff) {
            ShareToWebsite.setEnabled(false);
        }
    }

    public synchronized void save() {
        JsonObject obj = new JsonObject();
        FIELDS.save(this, obj);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Use {@link ShareToWebsite#setEnabled}, which also handles the delete. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isNoticeShown() {
        return noticeShown;
    }

    void setNoticeShown(boolean noticeShown) {
        this.noticeShown = noticeShown;
    }

    public boolean isPendingDelete() {
        return pendingDelete;
    }

    void setPendingDelete(boolean pendingDelete) {
        this.pendingDelete = pendingDelete;
    }
}
