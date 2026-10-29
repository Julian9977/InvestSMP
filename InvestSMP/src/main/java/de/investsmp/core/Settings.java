package de.investsmp.core;

import java.math.BigDecimal;

/** Laufzeit-Einstellungen. Werte in Cent bzw. Parts-per-Million (ppm). 1 % = 10.000 ppm. */
public final class Settings {
    /** Rendite pro Sekunde in ppm (1000 ppm = 0,1 %). */
    public volatile long ratePpm = 1000;
    public volatile long minInvest = 100;
    public volatile long maxTotalInvest = 1_000_000L * 100L;
    public volatile boolean paused = false;
    public volatile boolean offlineEnabled = true;
    public volatile long maxOfflineSeconds = 3600;
    public volatile boolean defaultAutocollect = true;
    public volatile long maxOnlineCatchupSeconds = 60;
    public volatile long payoutLogIntervalSeconds = 300;
    public volatile long startingBalance = 0;

    public String ratePercentString() {
        return BigDecimal.valueOf(ratePpm).movePointLeft(4).stripTrailingZeros().toPlainString();
    }
}
