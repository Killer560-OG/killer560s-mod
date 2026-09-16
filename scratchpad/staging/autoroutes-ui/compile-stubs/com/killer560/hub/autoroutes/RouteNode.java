package com.killer560.hub.autoroutes;
/** COMPILE-CHECK STUB ONLY - not for integration. Mirrors the API the core agent declared. */
public record RouteNode(Type type) {
    public enum Type { START, WALK, ETHERWARP, USE_ITEM, DUNGEON_BREAKER, BOOM, AWAIT, ROTATE, UNSNEAK, COMMAND }
}
