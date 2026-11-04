package com.killer560.hub.puzzlesolvers;

/** The change-only room and scan log lines every puzzle solver writes: a line goes out only when its key changes. */
final class SolverLog {

    private final org.slf4j.Logger logger;
    private final String tag;
    /** The last room state logged; "none" outside the solver's room. */
    String roomKey = "none";
    /** The last scan result logged; null after a room change. */
    String scanKey = null;

    SolverLog(org.slf4j.Logger logger, String tag) {
        this.logger = logger;
        this.tag = tag;
    }

    void room(String key, String detail) {
        if (key.equals(roomKey)) return;
        roomKey = key;
        scanKey = null;
        logger.info("{} {}", tag, detail);
    }

    void scan(String key, String detail) {
        if (key.equals(scanKey)) return;
        scanKey = key;
        logger.info("{} {}", tag, detail);
    }
}
