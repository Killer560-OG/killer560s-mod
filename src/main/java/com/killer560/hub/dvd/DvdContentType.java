package com.killer560.hub.dvd;

public enum DvdContentType {
    TEXT("Text"),
    GIF("GIF");

    public final String displayName;

    DvdContentType(String displayName) {
        this.displayName = displayName;
    }

    public DvdContentType next() {
        DvdContentType[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
