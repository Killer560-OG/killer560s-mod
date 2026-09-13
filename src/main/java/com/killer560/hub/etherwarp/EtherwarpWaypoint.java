package com.killer560.hub.etherwarp;

import java.util.UUID;

/** One personal etherwarp/secret-location bookmark for the CURRENT dungeon run - see
 *  {@link EtherwarpFeature}'s class doc for why these are never persisted to disk or broadcast to
 *  anyone, unlike Posmsg's presets/party-shared markers. */
public final class EtherwarpWaypoint {
    public final String id = UUID.randomUUID().toString();
    public final String name;
    public final double x;
    public final double y;
    public final double z;

    public EtherwarpWaypoint(String name, double x, double y, double z) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
