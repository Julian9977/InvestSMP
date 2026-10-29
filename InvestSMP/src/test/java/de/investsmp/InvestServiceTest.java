package de.investsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.investsmp.core.EconomyService;
import de.investsmp.core.InternalBackend;
import de.investsmp.core.InvestService;
import de.investsmp.core.InvestService.Result;
import de.investsmp.core.Settings;
import de.investsmp.db.Database;
import de.investsmp.util.Money;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvestServiceTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Settings settings = new Settings();
    private Database db;
    private EconomyService eco;
    private InvestService invest;
    private final UUID p = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        settings.minInvest = 1L;
        db = Database.memory();
        eco = new EconomyService(db, new InternalBackend(db), clock::get);
        invest = new InvestService(db, eco, settings, clock::get);
        eco.ensureAccount(p, "Player", 100_000L, false); // 1.000 Coins, manuelles Abholen
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void advanceSeconds(long s) {
        clock.addAndGet(s * 1000L);
    }

    private long pending() {
        return invest.info(p).pending();
    }

    @Test
    void investingDeductsExactlyAndStoresInvestment() {
        assertEquals(Result.OK, invest.invest(p, 50_000L));
        assertEquals(50_000L, eco.balance(p));
        assertEquals(50_000L, invest.invested(p));
        assertEquals("0,50", Money.formatRate(invest.perSecondCoins(p))); // 500 Coins * 0,1 % = 0,50 Coins/s
    }

    @Test
    void multipleInvestmentsAddUpAndNeverOverwrite() {
        invest.invest(p, 10_000L);
        invest.invest(p, 20_000L);
        invest.invest(p, 5_000L);
        assertEquals(35_000L, invest.invested(p));
        assertEquals(3, invest.info(p).entries().size());
        assertEquals(65_000L, eco.balance(p));
    }

    @Test
    void notEnoughMoneyChangesNothing() {
        assertEquals(Result.INSUFFICIENT, invest.invest(p, 100_001L));
        assertEquals(100_000L, eco.balance(p));
        assertEquals(0L, invest.invested(p));
    }

    @Test
    void invalidLowHighAndPausedAreRejected() {
        settings.minInvest = 100L;
        settings.maxTotalInvest = 60_000L;
        assertEquals(Result.INVALID, invest.invest(p, -5L));
        assertEquals(Result.TOO_LOW, invest.invest(p, 50L));
        assertEquals(Result.TOO_HIGH, invest.invest(p, 60_001L));
        settings.paused = true;
        assertEquals(Result.PAUSED, invest.invest(p, 1_000L));
        assertEquals(100_000L, eco.balance(p));
        assertEquals(0L, invest.invested(p));
    }

    @Test
    void yieldFollowsElapsedTime() {
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        assertEquals(500L, pending()); // 0,5 Coins/s * 10 s = 5,00 Coins
        advanceSeconds(10);
        assertEquals(1_000L, pending());
    }

    @Test
    void newInvestmentSettlesOldYieldFirst() {
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        invest.invest(p, 50_000L); // ab jetzt doppelte Rendite
        assertEquals(500L, pending());
        advanceSeconds(10);
        assertEquals(500L + 1_000L, pending());
    }

    @Test
    void roundingRemainderIsNeverLost() {
        eco.ensureAccount(p, "Player", 0L, false);
        invest.invest(p, 1L); // 1 Cent * 0,1 %/s = 0,001 Cent/s
        for (int i = 0; i < 999; i++) {
            advanceSeconds(1);
            invest.accrue(p, clock.get(), 60);
        }
        assertEquals(0L, pending());
        advanceSeconds(1);
        assertEquals(1L, pending()); // nach genau 1000 s ist der Cent voll
    }

    @Test
    void ticksAndOneBigStepGiveTheSameResult() {
        invest.invest(p, 12_345L);
        for (int i = 0; i < 100; i++) {
            advanceSeconds(1);
            invest.tickAll(java.util.List.of(p));
        }
        long viaTicks = pending();
        long expected = 12_345L * 1000L * 100L / 1_000_000L; // 1234 Cent (abgerundet)
        assertEquals(expected, viaTicks);
    }

    @Test
    void autocollectBooksYieldDirectlyAndLedgerMatches() {
        invest.setAutocollect(p, true);
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        invest.tickAll(java.util.List.of(p));
        assertEquals(50_000L + 500L, eco.balance(p));
        assertEquals(0L, pending());
        invest.flushAll();
        long ledger = db.scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE uuid=?", p.toString());
        assertEquals(eco.balance(p), ledger);
    }

    @Test
    void collectMovesPendingOnceAndDoubleCommandPaysNothingTwice() {
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        assertEquals(500L, invest.collect(p));
        assertEquals(0L, invest.collect(p));
        assertEquals(0L, invest.collect(p));
        assertEquals(50_500L, eco.balance(p));
        assertEquals(0L, pending());
    }

    @Test
    void switchingToAutocollectPaysOutPending() {
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        assertEquals(500L, invest.setAutocollect(p, true));
        assertEquals(50_500L, eco.balance(p));
    }

    @Test
    void pausedInvestmentsEarnNothingAndResumeWithoutBackpay() {
        invest.invest(p, 50_000L);
        advanceSeconds(10);
        invest.setPlayerPaused(p, true);
        advanceSeconds(100);
        assertEquals(500L, pending());
        invest.setPlayerPaused(p, false);
        advanceSeconds(10);
        assertEquals(1_000L, pending());
    }

    @Test
    void globalPauseStopsYield() {
        invest.invest(p, 50_000L);
        settings.paused = true;
        advanceSeconds(60);
        assertEquals(0L, pending());
    }

    @Test
    void offlineEarningsRespectConfiguration() {
        invest.invest(p, 50_000L);
        invest.onQuit(p);

        settings.offlineEnabled = false;
        advanceSeconds(1_000);
        assertEquals(0L, invest.onJoin(p));
        assertEquals(0L, pending());

        invest.onQuit(p);
        settings.offlineEnabled = true;
        settings.maxOfflineSeconds = 60;
        advanceSeconds(10_000); // wird auf 60 s gekappt
        assertEquals(60L * 50L, invest.onJoin(p));
        assertEquals(3_000L, pending());
    }
}
