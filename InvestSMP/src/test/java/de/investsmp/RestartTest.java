package de.investsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.investsmp.core.EconomyService;
import de.investsmp.core.InternalBackend;
import de.investsmp.core.InvestService;
import de.investsmp.core.Settings;
import de.investsmp.db.Database;
import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Simuliert Server-Neustarts mit einer echten SQLite-Datei. */
class RestartTest {
    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(5_000_000L);
    private final UUID p = UUID.randomUUID();

    private record Stack(Database db, EconomyService eco, InvestService invest) { }

    private Stack boot(Settings s) {
        Database db = Database.file(new File(dir.toFile(), "investsmp.db"));
        EconomyService eco = new EconomyService(db, new InternalBackend(db), clock::get);
        return new Stack(db, eco, new InvestService(db, eco, s, clock::get));
    }

    @Test
    void dataSurvivesRestartAndNothingIsPaidTwice() {
        Settings s = new Settings();
        s.minInvest = 1L;

        Stack a = boot(s);
        a.eco().ensureAccount(p, "Player", 100_000L, false);
        a.invest().invest(p, 50_000L);
        clock.addAndGet(10_000L);
        a.invest().accrue(p, clock.get(), 60);
        assertEquals(500L, a.invest().info(p).pending());
        a.db().close(); // Server stoppt

        Stack b = boot(s); // Server startet neu, gleiche Uhrzeit
        assertEquals(50_000L, b.eco().balance(p));
        assertEquals(50_000L, b.invest().invested(p));
        assertEquals(500L, b.invest().info(p).pending());
        assertEquals(0L, b.invest().accrue(p, clock.get(), 60)); // keine Doppelauszahlung
        assertEquals(0L, b.invest().onJoin(p));
        assertEquals(500L, b.invest().info(p).pending());

        clock.addAndGet(10_000L);
        assertEquals(500L, b.invest().accrue(p, clock.get(), 60));
        assertEquals(1_000L, b.invest().info(p).pending());
        b.db().close();
    }

    @Test
    void existingAccountIsNotResetAfterRestart() {
        Settings s = new Settings();
        Stack a = boot(s);
        a.eco().ensureAccount(p, "Player", 100_000L, true);
        a.eco().deposit(p, 2_500L, "TEST", "t", "");
        a.db().close();

        Stack b = boot(s);
        b.eco().ensureAccount(p, "Player", 100_000L, true); // erneuter Login
        assertEquals(102_500L, b.eco().balance(p));
        b.db().close();
    }

    @Test
    void downtimeIsTreatedAsOfflineTimeWithCap() {
        Settings s = new Settings();
        s.minInvest = 1L;
        s.maxOfflineSeconds = 3_600;

        Stack a = boot(s);
        a.eco().ensureAccount(p, "Player", 100_000L, false);
        a.invest().invest(p, 50_000L);
        a.invest().onQuit(p);
        a.db().close();

        clock.addAndGet(10L * 3_600L * 1_000L); // 10 Stunden Ausfall
        Stack b = boot(s);
        assertEquals(3_600L * 50L, b.invest().onJoin(p)); // nur 1 Stunde angerechnet
        assertEquals(3_600L * 50L, b.invest().info(p).pending());
        b.db().close();
    }

    @Test
    void unflushedAutoPayoutsAreLoggedAfterRestart() {
        Settings s = new Settings();
        s.minInvest = 1L;
        Stack a = boot(s);
        a.eco().ensureAccount(p, "Player", 100_000L, true);
        a.invest().invest(p, 50_000L);
        clock.addAndGet(20_000L);
        a.invest().tickAll(java.util.List.of(p));
        a.db().close(); // Absturz ohne flush

        Stack b = boot(s);
        b.invest().flushAll();
        long ledger = b.db().scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE uuid=?", p.toString());
        assertEquals(b.eco().balance(p), ledger);
        assertEquals(50_000L + 1_000L, b.eco().balance(p));
        b.db().close();
    }
}
