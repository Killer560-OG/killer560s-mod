package com.killer560.hub.testing;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.FolderTab;

import java.util.List;

/** The testing build's "Untested" category: every feature tab not yet marked tested (see {@link TestingMenu}). */
public final class UntestedTab extends FolderTab {

    public UntestedTab(List<BaseTab> untested) {
        super(TestingMenu.UNTESTED, untested);
    }
}
