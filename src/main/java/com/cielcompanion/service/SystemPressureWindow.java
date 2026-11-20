package com.cielcompanion.service;

/** Requires persistent measured pressure; a single busy sampling tick is not overload. */
public final class SystemPressureWindow {
    private long highSince = -1;
    private long lastObserved = -1;
    public synchronized boolean observe(double cpu, double ram, long now) {
        boolean high = Double.isFinite(cpu) && cpu > 95 && cpu <= 100
                || Double.isFinite(ram) && ram > 95 && ram <= 100;
        if (!high || now < lastObserved || lastObserved >= 0 && now - lastObserved > 90_000)
            highSince = -1;
        lastObserved = now;
        if (!high) return false;
        if (highSince < 0) highSince = now;
        return now - highSince >= 45_000;
    }
}
