package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping every chat-affecting feature: Translate first (so "/language" - which opens
 *  this tab directly - still lands on it by default), then Auto Correct, Chat Emotes, Click
 *  Translate, Copy Chat (2026-09-09, killer560's "Ctrl+Click to copy" roadmap request - grouped right
 *  next to Click Translate since both hook the same chat-click infrastructure), Auto Meow, Cringe,
 *  Spotify Mod (moved in from its own top-level slot, 2026-09-07, per killer560's re-categorization
 *  request), and Screenshot Copy (moved in from Home, 2026-09-08, per killer560's explicit request to
 *  give it its own category here). */
public class ChatTab extends FolderTab {

    public ChatTab() {
        super("Chat", List.of(
                new TranslateTab(),
                new AutoCorrectTab(),
                new ChatEmotesTab(),
                new ClickTranslateTab(),
                new CopyChatTab(),
                // Peek Chat (2026-10-13, killer560): read the chat without a text box to type in.
                new PeekChatTab(),
                new AutoMeowTab(),
                new CringeTab(),
                new SpotifyTab(),
                new ScreenshotCopyTab(),
                // Confirmed working 2026-09-16 ("voice to text works perfectly"), moved out of New.
                new VoiceToTextTab(),
                // Chat Hider (2026-10-08, killer560): Hide Chat Messages (Object Hider's old "Chat Replacements") and
                // Chat Tidy (stack repeated lines, hide Hypixel's damage spam) in one tab under one master switch.
                new ChatHiderTab(),
                // Out of the New category, which was removed 2026-10-07 (killer560: every feature moves to the
                // category it lives in for the release).
                new ModChatTab(),
                new PartyCommandsTab(),
                // Chat Keybinds is also Command Shortcuts since 2026-10-08 (killer560: "Chat keybinds and command
                // shortcuts should be combined into just chat keybinds.").
                new CommandKeybindsTab()
        ));
    }
}
