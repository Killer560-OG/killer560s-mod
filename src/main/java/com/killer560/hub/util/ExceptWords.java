package com.killer560.hub.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps a free-text argument so that it refuses a whole value equal to a sibling literal. Brigadier reports an
 * "Ambiguity between arguments" warning at every launch when a literal ({@code reset}) sits beside an argument that
 * would also accept the same text; the literal already won at run time, so refusing the exact word here changes
 * nothing the player types and only removes the overlap the check looks for.
 *
 * <p>Only the whole parsed value is compared, so {@code stop} is refused but {@code stop the bus} is still a
 * destination, as before.
 */
public final class ExceptWords<T> implements ArgumentType<T> {
    private static final SimpleCommandExceptionType RESERVED =
            new SimpleCommandExceptionType(() -> "Reserved word");

    private final ArgumentType<T> inner;
    private final Set<String> words;

    private ExceptWords(ArgumentType<T> inner, Set<String> words) {
        this.inner = inner;
        this.words = words;
    }

    public static <T> ExceptWords<T> of(ArgumentType<T> inner, String... words) {
        return new ExceptWords<>(inner, Set.of(words));
    }

    @Override
    public T parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        T value = inner.parse(reader);
        if (value instanceof String s && words.contains(s)) {
            reader.setCursor(start);
            throw RESERVED.createWithContext(reader);
        }
        return value;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return inner.listSuggestions(context, builder);
    }

    @Override
    public Collection<String> getExamples() {
        return inner.getExamples().isEmpty() ? List.of() : inner.getExamples();
    }
}
