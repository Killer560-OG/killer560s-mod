package com.killer560.hub.carnival;



/** Carnival Tracker settings (see {@link CarnivalTracker}). Display only; off by default. */
public final class CarnivalTrackerConfig extends CarnivalSettings {

    private static final CarnivalTrackerConfig INSTANCE = new CarnivalTrackerConfig();

    private CarnivalTrackerConfig() {
        super("tracker");
        declare("enabled", false);
        declare("showGame", true);
        declare("showSession", true);
        declare("showBest", true);
        declare("showGoals", true);
        declare("ticketReminder", true);
    }

    public static CarnivalTrackerConfig getInstance() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public static void load() {
        INSTANCE.reload();
    }

    public boolean isShowGame() {
        return bool("showGame");
    }

    public void setShowGame(boolean v) {
        put("showGame", v);
    }

    public boolean isShowSession() {
        return bool("showSession");
    }

    public void setShowSession(boolean v) {
        put("showSession", v);
    }

    public boolean isShowBest() {
        return bool("showBest");
    }

    public void setShowBest(boolean v) {
        put("showBest", v);
    }

    public boolean isShowGoals() {
        return bool("showGoals");
    }

    public void setShowGoals(boolean v) {
        put("showGoals", v);
    }

    public boolean isTicketReminder() {
        return bool("ticketReminder");
    }

    public void setTicketReminder(boolean v) {
        put("ticketReminder", v);
    }
}
