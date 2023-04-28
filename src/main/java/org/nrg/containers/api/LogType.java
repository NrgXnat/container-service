package org.nrg.containers.api;

public enum LogType {
    STDOUT,
    STDERR;

    public String logName() {
        return this.name().toLowerCase() + ".log";
    }
}
