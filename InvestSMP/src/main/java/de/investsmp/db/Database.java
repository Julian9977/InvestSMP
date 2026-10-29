package de.investsmp.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Dünne SQLite-Schicht mit EINER Verbindung. Alle Zugriffe sind über ein Lock serialisiert,
 * Transaktionen ({@link #tx}) sind verschachtelbar (innere Aufrufe nehmen an der äußeren teil).
 * Wird eine Exception geworfen, wird die gesamte äußere Transaktion zurückgerollt.
 */
public final class Database implements AutoCloseable {

    public static final class DbException extends RuntimeException {
        public DbException(Throwable cause) {
            super(String.valueOf(cause.getMessage()), cause);
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection conn;
    private final ReentrantLock lock = new ReentrantLock();
    private int depth;

    private Database(String url) {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS accounts("
                        + "uuid TEXT PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "balance INTEGER NOT NULL DEFAULT 0 CHECK(balance >= 0),"
                        + "pending INTEGER NOT NULL DEFAULT 0 CHECK(pending >= 0),"
                        + "unlogged INTEGER NOT NULL DEFAULT 0 CHECK(unlogged >= 0),"
                        + "remainder INTEGER NOT NULL DEFAULT 0,"
                        + "last_accrual INTEGER NOT NULL DEFAULT 0,"
                        + "autocollect INTEGER NOT NULL DEFAULT 1,"
                        + "paused INTEGER NOT NULL DEFAULT 0,"
                        + "created INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS investments("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT NOT NULL,"
                        + "amount INTEGER NOT NULL CHECK(amount > 0),"
                        + "created INTEGER NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_investments_uuid ON investments(uuid)");
                st.execute("CREATE TABLE IF NOT EXISTS transactions("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "ts INTEGER NOT NULL,"
                        + "uuid TEXT NOT NULL,"
                        + "type TEXT NOT NULL,"
                        + "amount INTEGER NOT NULL,"
                        + "balance_after INTEGER NOT NULL,"
                        + "actor TEXT NOT NULL,"
                        + "note TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_tx_uuid ON transactions(uuid, id)");
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new DbException(e);
        }
    }

    public static Database file(File f) {
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return new Database("jdbc:sqlite:" + f.getAbsolutePath());
    }

    public static Database memory() {
        return new Database("jdbc:sqlite::memory:");
    }

    /** Führt die Arbeit atomar aus. */
    public <T> T tx(Supplier<T> work) {
        lock.lock();
        try {
            boolean outer = depth == 0;
            if (outer) {
                try {
                    conn.setAutoCommit(false);
                } catch (SQLException e) {
                    throw new DbException(e);
                }
            }
            depth++;
            try {
                T result = work.get();
                if (outer) {
                    conn.commit();
                }
                return result;
            } catch (SQLException e) {
                if (outer) {
                    rollbackQuietly();
                }
                throw new DbException(e);
            } catch (RuntimeException | Error e) {
                if (outer) {
                    rollbackQuietly();
                }
                throw e;
            } finally {
                depth--;
                if (outer) {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // Verbindung ist ohnehin defekt
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void run(Runnable work) {
        tx(() -> {
            work.run();
            return null;
        });
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // nichts mehr zu tun
        }
    }

    public int update(String sql, Object... params) {
        lock.lock();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            lock.unlock();
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        lock.lock();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            lock.unlock();
        }
    }

    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    /** Liefert die erste Spalte der ersten Zeile oder 0. */
    public long scalar(String sql, Object... params) {
        return one(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            conn.close();
        } catch (SQLException ignored) {
            // egal beim Beenden
        } finally {
            lock.unlock();
        }
    }
}
