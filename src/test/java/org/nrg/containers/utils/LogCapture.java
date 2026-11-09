package org.nrg.containers.utils;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.rules.ExternalResource;

import java.util.ArrayList;
import java.util.List;

/** Captures the rendered messages one class logs during a test, at a chosen level; restores the logger afterwards. */
public class LogCapture extends ExternalResource {
    private final Logger logger;
    private final List<String> messages = new ArrayList<>();
    private final AppenderSkeleton appender = new AppenderSkeleton() {
        @Override
        protected void append(final LoggingEvent event) {
            messages.add(event.getRenderedMessage());
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    };
    private Level originalLevel;

    public LogCapture(final Class<?> loggingClass) {
        this.logger = Logger.getLogger(loggingClass);
    }

    @Override
    protected void before() {
        originalLevel = logger.getLevel();
        logger.addAppender(appender);
    }

    @Override
    protected void after() {
        logger.removeAppender(appender);
        logger.setLevel(originalLevel);
    }

    public void setLevel(final Level level) {
        logger.setLevel(level);
    }

    /** Everything captured so far, one message per line. */
    public String messages() {
        return String.join("\n", messages);
    }
}
