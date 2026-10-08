package com.killer560.hub.gui.tab;

import java.util.List;

/** The Social category (2026-10-07): the README's "Social & Supporters" features, moved here out of the New category
 *  when killer560 removed it ("every feature ... moves to the category where it will live in the final release"). */
public class SocialTab extends FolderTab {

    public SocialTab() {
        super("Social", List.of(
                new BestFriendsTab(),
                new FriendsListTab(),
                new ProfileViewerTab()
                // Cosmetics and Nickhider moved to the Cosmetics category (2026-10-08).
        ));
    }
}
