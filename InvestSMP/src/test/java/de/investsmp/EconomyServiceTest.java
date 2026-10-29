package de.investsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.investsmp.core.EconomyService;
import de.investsmp.core.EconomyService.Result;
import de.investsmp.core.InternalBackend;
import de.investsmp.db.Database;
import de.investsmp.util.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EconomyServiceTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Database db;
    private EconomyService eco;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        db = Database.memory();
        eco = new EconomyService(db, new InternalBackend(db), clock::get);
        eco.ensureAccount(alice, "Alice", 100_000L, true); // 1.000,00 Coins
        eco.ensureAccount(bob, "Bob", 0L, true);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private long ledger(UUID id) {
        return db.scalar("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE uuid=?", id.toString());
    }

    @Test
    void startingBalanceIsBooked() {
        assertEquals(100_000L, eco.balance(alice));
        assertEquals(0L, eco.balance(bob));
        assertEquals(100_000L, ledger(alice));
    }

    @Test
    void accountIsNotRecreatedOrResetOnSecondJoin() {
        eco.deposit(alice, 500L, "TEST", "t", "");
        eco.ensureAccount(alice, "AliceNeu", 100_000L, true);
        assertEquals(100_500L, eco.balance(alice));
        assertEquals("AliceNeu", eco.nameOf(alice));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertEquals(Result.INVALID, eco.deposit(alice, -5L, "T", "t", ""));
        assertEquals(Result.INVALID, eco.deposit(alice, 0L, "T", "t", ""));
        assertEquals(Result.INVALID, eco.withdraw(alice, -1L, "T", "t", ""));
        assertEquals(Result.INVALID, eco.transfer(alice, bob, -100L, "Alice", "Bob"));
        assertEquals(100_000L, eco.balance(alice));
        assertEquals(0L, eco.balance(bob));
    }

    @Test
    void moneyParserRejectsGarbage() {
        for (String bad : new String[] {"-5", "abc", "NaN", "Infinity", "1e5", "0", "0,00", "1.234", "", " ", "1,2,3",
            "99999999999999", "+5"}) {
            assertTrue(Money.parse(bad).isEmpty(), "sollte abgelehnt werden: '" + bad + "'");
        }
        assertEquals(50_000L, Money.parse("500").getAsLong());
        assertEquals(1_250L, Money.parse("12,5").getAsLong());
        assertEquals(1_205L, Money.parse("12.05").getAsLong());
    }

    @Test
    void formatsGermanStyle() {
        assertEquals("1.000,00", Money.format(100_000L));
        assertEquals("0,50", Money.format(50L));
    }

    @Test
    void withdrawBeyondBalanceFailsAndChangesNothing() {
        assertEquals(Result.INSUFFICIENT, eco.withdraw(alice, 100_001L, "T", "t", ""));
        assertEquals(100_000L, eco.balance(alice));
        assertEquals(100_000L, ledger(alice));
    }

    @Test
    void transferMovesExactAmountAndIsLogged() {
        assertEquals(Result.OK, eco.transfer(alice, bob, 25_050L, "Alice", "Bob"));
        assertEquals(74_950L, eco.balance(alice));
        assertEquals(25_050L, eco.balance(bob));
        assertEquals(eco.balance(alice), ledger(alice));
        assertEquals(eco.balance(bob), ledger(bob));
    }

    @Test
    void transferToSelfAndWithoutFundsChangesNothing() {
        assertEquals(Result.SAME_ACCOUNT, eco.transfer(alice, alice, 100L, "Alice", "Alice"));
        assertEquals(Result.INSUFFICIENT, eco.transfer(bob, alice, 100L, "Bob", "Alice"));
        assertEquals(Result.NO_ACCOUNT, eco.transfer(alice, UUID.randomUUID(), 100L, "Alice", "?"));
        assertEquals(100_000L, eco.balance(alice));
        assertEquals(0L, eco.balance(bob));
    }

    @Test
    void depositBeyondMaximumIsRejected() {
        assertEquals(Result.LIMIT, eco.deposit(alice, Money.MAX_BALANCE, "T", "t", ""));
        assertEquals(100_000L, eco.balance(alice));
    }

    @Test
    void adminSetBalanceIsLoggedWithActor() {
        assertEquals(Result.OK, eco.setBalance(alice, 123_456L, "Admin1", "Test"));
        assertEquals(123_456L, eco.balance(alice));
        assertEquals(eco.balance(alice), ledger(alice));
        EconomyService.TxEntry last = eco.history(alice, 1).get(0);
        assertEquals("ADMIN_SET", last.type());
        assertEquals("Admin1", last.actor());
        assertEquals(Result.INVALID, eco.setBalance(alice, -1L, "Admin1", ""));
    }

    @Test
    void randomTransfersNeverCreateOrDestroyMoney() {
        Random rnd = new Random(42);
        for (int i = 0; i < 500; i++) {
            boolean aliceToBob = rnd.nextBoolean();
            eco.transfer(aliceToBob ? alice : bob, aliceToBob ? bob : alice, 1 + rnd.nextInt(30_000), "x", "y");
        }
        assertEquals(100_000L, eco.balance(alice) + eco.balance(bob));
        assertEquals(eco.balance(alice), ledger(alice));
        assertEquals(eco.balance(bob), ledger(bob));
    }

    @Test
    void commandSpamCannotOverdrawOrDuplicate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Result>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(() -> eco.transfer(alice, bob, 5_000L, "Alice", "Bob"));
            }
            int ok = 0;
            for (Future<Result> f : pool.invokeAll(tasks)) {
                if (f.get() == Result.OK) {
                    ok++;
                }
            }
            assertEquals(20, ok); // 100.000 / 5.000
            assertEquals(0L, eco.balance(alice));
            assertEquals(100_000L, eco.balance(bob));
            assertEquals(100_000L, eco.balance(alice) + eco.balance(bob));
        } finally {
            pool.shutdownNow();
        }
    }
}
