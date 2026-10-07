package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * Binds SLF4J 1.7, which the AWS SDK logs through, to a logger that drops everything. Without a binding,
 * SLF4J prints a notice of three lines to the error stream the first time the SDK logs, which the
 * {@code falcon} command would show with every S3 request's output. SLF4J 1.7 finds its binding by this
 * class's name; this is the one the command's jar carries.
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder {

    /** The SLF4J API version this binding is compiled against, which SLF4J checks. */
    public static String REQUESTED_API_VERSION = "1.6.99";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private final ILoggerFactory factory = new NOPLoggerFactory();

    private StaticLoggerBinder() {
    }

    /** {@return the binding, as SLF4J asks for it} */
    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return NOPLoggerFactory.class.getName();
    }
}
