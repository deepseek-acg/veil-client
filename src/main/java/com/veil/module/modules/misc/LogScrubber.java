// lang: Java, file: LogScrubber.java, target: Fabric MC 26.3
// Intercepts Log4j2 at module enable and suppresses records that name mist classes,
// so the game log does not accumulate stack traces during normal play.
package com.veil.module.modules.misc;

import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

public final class LogScrubber extends Module {

    private MistLogFilter activeFilter = null;

    public LogScrubber() {
        super("LogScrubber", "Suppresses veil class names from the game log", Category.MISC);
    }

    @Override
    public void onEnable() {
        if (activeFilter != null) return;
        activeFilter = new MistLogFilter();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().addFilter(activeFilter);
        ctx.updateLoggers();
    }

    @Override
    public void onDisable() {
        if (activeFilter == null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().removeFilter(activeFilter);
        ctx.updateLoggers();
        activeFilter = null;
    }

    private static final class MistLogFilter extends AbstractFilter {

        @Override
        public Result filter(LogEvent event) {
            return check(event.getMessage() != null ? event.getMessage().getFormattedMessage() : null,
                    event.getLoggerName());
        }

        @Override
        public Result filter(Logger logger, org.apache.logging.log4j.Level level, Marker marker,
                             String msg, Object... params) {
            return check(msg, logger.getName());
        }

        @Override
        public Result filter(Logger logger, org.apache.logging.log4j.Level level, Marker marker,
                             Object msg, Throwable t) {
            return check(msg != null ? msg.toString() : null, logger.getName());
        }

        @Override
        public Result filter(Logger logger, org.apache.logging.log4j.Level level, Marker marker,
                             org.apache.logging.log4j.message.Message msg, Throwable t) {
            return check(msg != null ? msg.getFormattedMessage() : null, logger.getName());
        }

        private static Result check(String message, String loggerName) {
            if (loggerName != null && loggerName.startsWith("com.veil")) return Result.DENY;
            if (message == null) return Result.NEUTRAL;
            String lower = message.toLowerCase();
            if (lower.contains("com.veil") || lower.contains("veilclient")) return Result.DENY;
            // Check against live module names to catch any formatted output referencing them.
            for (Module m : ModuleManager.getModules()) {
                if (message.contains(m.getName())) return Result.DENY;
            }
            return Result.NEUTRAL;
        }
    }
}
