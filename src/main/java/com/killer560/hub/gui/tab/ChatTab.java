package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping every chat-affecting feature: Translate first (so "/language" - which opens
 *  this tab directly - still lands on it by default), then Auto Correct, Chat Emotes, Click
 *  Translate, Auto Meow, Cringe, Spotify Mod (moved in from its own top-level slot, 2026-09-07,
 *  per killer560's re-categorization request), and Screenshot Copy (moved in from Home, 2026-09-08,
 *  per killer560's explicit request to give it its own category here). */
public class ChatTab extends FolderTab {

    public ChatTab() {
        super("Chat", List.of(
                new TranslateTab(),
                new AutoCorrectTab(),
                new ChatEmotesTab(),
                new ClickTranslateTab(),
                new AutoMeowTab(),
                new CringeTab(),
                new SpotifyTab(),
                new ScreenshotCopyTab()
        ));
    }
}
