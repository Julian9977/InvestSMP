package de.investsmp.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;

/** Geldbeträge werden intern IMMER als ganze Cent (long) geführt – nie als double. */
public final class Money {

    /** Maximaler Kontostand: 1.000.000.000.000 Coins (in Cent). */
    public static final long MAX_BALANCE = 1_000_000_000_000L * 100L;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd.MM.yy HH:mm:ss").withZone(ZoneId.systemDefault());

    private Money() {
    }

    public static String format(long cents) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY))
                .format(BigDecimal.valueOf(cents, 2));
    }

    /** Für Erträge pro Sekunde: mindestens 2, höchstens 4 Nachkommastellen. */
    public static String formatRate(BigDecimal coins) {
        return new DecimalFormat("#,##0.00##", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(coins);
    }

    public static String date(long epochMillis) {
        return TS.format(Instant.ofEpochMilli(epochMillis));
    }

    public static long coinsToCents(double coins) {
        return Math.round(coins * 100.0);
    }

    /**
     * Parst "500", "500.5" oder "500,5" (max. 2 Nachkommastellen) zu Cent.
     * Lehnt negative Werte, 0, NaN, Exponenten, Tausendertrenner und Überläufe ab.
     */
    public static OptionalLong parse(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        String s = raw.trim();
        if (!s.matches("\\d{1,13}([.,]\\d{1,2})?")) {
            return OptionalLong.empty();
        }
        long cents = new BigDecimal(s.replace(',', '.')).movePointRight(2).longValueExact();
        if (cents <= 0 || cents > MAX_BALANCE) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(cents);
    }
}
