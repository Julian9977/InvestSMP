package de.investsmp.core;

import java.util.UUID;

/** Das EINE System, das Kontostände führt (intern oder Vault). Beträge in Cent. */
public interface MoneyBackend {
    String name();

    boolean isInternal();

    boolean hasAccount(UUID id);

    void createAccount(UUID id, String name);

    long balance(UUID id);

    /** @return false, wenn nicht genug Guthaben vorhanden ist. */
    boolean withdraw(UUID id, long cents);

    /** @return false, wenn das Konto fehlt oder das Maximum überschritten würde. */
    boolean deposit(UUID id, long cents);
}
