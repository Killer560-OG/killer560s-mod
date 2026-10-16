package com.killer560.hub.util;

import com.killer560.hub.BuildVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

import java.util.Arrays;

/**
 * Every logger in this mod comes from here instead of straight from {@link LoggerFactory}, so that ONE switch
 * decides whether a build logs at all.
 *
 * <p>killer560 asked (2026-09-29) for a release jar that emits no mod logging: "I plan on removing any and all
 * loggers when I go to make the true release". Wrapping a thousand call sites in
 * {@code if (BuildVariant.DEV_TOOLS)} would have been a thousand chances to get one wrong, so the decision is
 * made once, here, at logger construction:
 *
 * <ul>
 *   <li><b>dev and cheat builds</b> (the default, {@code DEV_TOOLS == true}) get the real SLF4J logger and log
 *       exactly as before - the diagnostic lines he uses to find real bugs are untouched;</li>
 *   <li><b>release builds</b> ({@code -Prelease=true}, {@code DEV_TOOLS == false}) get a logger that drops
 *       TRACE, DEBUG, INFO and WARN outright, and forwards only ERROR.</li>
 * </ul>
 *
 * <p>ERROR survives deliberately. Everything at that level in this mod is a caught exception that has just
 * disabled a feature, failed a config write or failed a network call; silencing those would leave him with a
 * feature that does nothing and no way at all to find out why. If he wants a truly silent jar, changing
 * {@link #get} to return {@code org.slf4j.helpers.NOPLogger.NOP_LOGGER} is the one-line way to get it.
 *
 * <p>Because {@code isInfoEnabled()} is false in a release, an {@code if (LOGGER.isDebugEnabled())} guard
 * keeps working and the message arguments are never even formatted.
 */
public final class ModLog {

    private ModLog() {
    }

    /** The mod's logger for {@code name}: the real one in dev and cheat builds, an error-only one in a release. */
    public static Logger get(String name) {
        Logger real = LoggerFactory.getLogger(name);
        return BuildVariant.DEV_TOOLS ? real : new ReleaseLogger(real);
    }

    /**
     * {@code logger}, with its TRACE/DEBUG/INFO lines dropped while {@code quiet} says so (WARN and ERROR always pass).
     * For a class whose INFO lines describe one user action each, when it is also run many times over in the
     * background - the Interactive Map's planner precomputing trips (livemap/autoclear/TripCache) would otherwise write
     * hundreds of "[Path] N warp(s)" lines nobody asked for.
     */
    public static Logger mutedWhen(Logger logger, java.util.function.BooleanSupplier quiet) {
        return new MutedLogger(logger, quiet);
    }

    private static final class MutedLogger extends LegacyAbstractLogger {

        private final transient Logger delegate;
        private final transient java.util.function.BooleanSupplier quiet;

        MutedLogger(Logger delegate, java.util.function.BooleanSupplier quiet) {
            this.delegate = delegate;
            this.quiet = quiet;
            this.name = delegate.getName();
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return MutedLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
                                                   Object[] arguments, Throwable throwable) {
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (throwable != null) {
                args = Arrays.copyOf(args, args.length + 1);
                args[args.length - 1] = throwable;
            }
            switch (level) {
                case ERROR -> delegate.error(pattern, args);
                case WARN -> delegate.warn(pattern, args);
                case INFO -> delegate.info(pattern, args);
                case DEBUG -> delegate.debug(pattern, args);
                default -> delegate.trace(pattern, args);
            }
        }

        @Override
        public boolean isTraceEnabled() {
            return delegate.isTraceEnabled() && !quiet.getAsBoolean();
        }

        @Override
        public boolean isDebugEnabled() {
            return delegate.isDebugEnabled() && !quiet.getAsBoolean();
        }

        @Override
        public boolean isInfoEnabled() {
            return delegate.isInfoEnabled() && !quiet.getAsBoolean();
        }

        @Override
        public boolean isWarnEnabled() {
            return delegate.isWarnEnabled();
        }

        @Override
        public boolean isErrorEnabled() {
            return delegate.isErrorEnabled();
        }
    }

    /**
     * Drops everything below ERROR. {@link LegacyAbstractLogger} does the varargs/marker/throwable
     * normalisation, so only one method has to decide anything.
     */
    private static final class ReleaseLogger extends LegacyAbstractLogger {

        private final transient Logger delegate;

        ReleaseLogger(Logger delegate) {
            this.delegate = delegate;
            this.name = delegate.getName();
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return ReleaseLogger.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
                                                   Object[] arguments, Throwable throwable) {
            if (level != Level.ERROR) {
                return;
            }
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (throwable != null) {
                // SLF4J pulls a trailing Throwable back out itself, so this keeps the stack trace.
                args = Arrays.copyOf(args, args.length + 1);
                args[args.length - 1] = throwable;
            }
            delegate.error(pattern, args);
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return delegate.isErrorEnabled();
        }
    }
}
