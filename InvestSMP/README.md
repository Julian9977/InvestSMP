# InvestSMP – Money- und Investitions-Plugin für Paper

Economy (Coins), Investitionen mit Rendite pro Sekunde, SQLite-Speicherung, Vault-Anbindung,
Admin-GUI / Server-Dashboard und eigene Befehle für Minecraft-SMP-Server.

* Server: **Paper **26.2** (API 26.2.build.134-stable), **Java 25**
* Optional: **Vault** (wird bevorzugt genutzt, wenn eine Economy installiert ist)

## Bauen

```bash
mvn clean package          # baut + führt die Tests aus
mvn clean package -DskipTests
```

Die fertige Datei liegt in `target/InvestSMP-1.0.0.jar`.
SQLite (`sqlite-jdbc`) lädt Paper beim ersten Start automatisch über den `libraries:`-Eintrag der
`plugin.yml` herunter (Internetzugang des Servers beim ersten Start nötig).

## Installation

1. `InvestSMP-1.0.0.jar` in den Ordner `plugins/` legen (optional: Vault dazu).
2. Server starten – es entstehen `plugins/InvestSMP/config.yml`, `messages.yml`, `commands.yml` und `investsmp.db`.
3. `config.yml` anpassen (Rendite, Startguthaben, Limits) und `/investsmp reload` ausführen.

### Economy-Verhalten (nur EIN System führt Kontostände)

| Situation | Verhalten |
|---|---|
| Vault + fremde Economy (z. B. EssentialsX) vorhanden | InvestSMP nutzt diese Economy, führt **keine** eigenen Kontostände und überschreibt nichts |
| Vault vorhanden, keine Economy | interne SQLite-Economy, wird mit normaler Priorität bei Vault angemeldet |
| Kein Vault | interne SQLite-Economy |
| `economy.mode: INTERNAL` | immer intern |

Hinweis: EssentialsX & Co. besitzen ebenfalls `/money`, `/pay`, `/baltop`. Nutze dann `/investsmp:money` usw.
oder deaktiviere die Befehle dort (`disabled-commands`).

## Befehle

| Befehl | Beschreibung | Recht |
|---|---|---|
| `/money` | eigener Kontostand | `investsmp.money` (alle) |
| `/money <spieler>` | Kontostand eines Spielers | `investsmp.money.others` (OP) |
| `/pay <spieler> <betrag>` | Coins überweisen | `investsmp.pay` (alle) |
| `/baltop` | reichste Spieler | `investsmp.baltop` (alle) |
| `/invest` | Investitionsmenü (GUI) | `investsmp.invest` (alle) |
| `/invest add <betrag>` | Coins investieren | `investsmp.invest` |
| `/invest info` | Investitionen und Erträge | `investsmp.invest` |
| `/invest collect` | angesammelte Erträge abholen | `investsmp.invest` |
| `/invest autocollect [on\|off]` | automatisches Abholen ein/aus/umschalten | `investsmp.invest` |
| `/investsmp` | Admin-GUI (= Dashboard) | `investsmp.admin` (OP) |
| `/investsmp reload` | Config, Nachrichten, eigene Befehle neu laden | `investsmp.admin` |
| `/dashboard` (Alias `/dashbord`) | Server-Dashboard | `investsmp.admin` |

Beträge: positive Zahlen mit max. 2 Nachkommastellen (`500`, `12,5`, `12.05`). Ausgabe immer als `1.000,00 Coins`.

## Dashboard / Admin-GUI

Statistik (Geld im Umlauf, Investiert, Konten, Rendite, Economy-System) und:

* **Spieler verwalten:** Spieler wählen → Geld ±Schnellbeträge, beliebigen Betrag per Chat hinzufügen / abziehen /
  Kontostand setzen, Investitionen prüfen, Investitionen des Spielers pausieren, Transaktionen des Spielers
* **Transaktionsverlauf** aller Spieler
* **Rendite & Limits:** Rendite (±0,01 % oder tippen), Mindest-/Höchstinvestition, Offline-Erträge, Standard-Auto-Abholen
* **Investitionen global pausieren**, **Plugin neu laden**, **Eigene Befehle** anzeigen

Alle Admin-Geldänderungen stehen mit Admin-Name im Transaktionsprotokoll und in der Server-Konsole (`[ADMIN] ...`).
Eingaben im Chat lassen sich mit `abbrechen` beenden.

## Investitionssystem

* Rendite pro Sekunde = investiert × Rate (`invest.rate-percent-per-second`, Beispiel 0,1 % → 500 Coins = 0,50 Coins/s).
* Mehrere Investitionen werden zusammengerechnet, neue überschreiben nie alte.
* Intern wird mit **ganzen Cent** gerechnet; der Rundungsrest (Bruchteil eines Cents) wird gespeichert und geht nie verloren.
* Abrechnung nach tatsächlich vergangener Zeit; `last_accrual` wird nur um volle Sekunden weitergeschoben →
  keine Doppelauszahlung bei Neustart.
* Auszahlungsmodus pro Spieler: **automatisch** (jede Sekunde gutgeschrieben) oder **manuell** (`/invest collect`).
* Offline-Erträge: `invest.offline-earnings` (ein/aus, maximal angerechnete Sekunden). Server-Ausfallzeit zählt als Offline-Zeit.
* Automatische Auszahlungen werden im Protokoll alle `payout-log-interval-seconds` zusammengefasst
  (`INVEST_PAYOUT`), damit die Datenbank nicht durch Sekunden-Einträge anwächst. Die Summe aller Protokolleinträge
  entspricht stets dem Kontostand.

## Sicherheit

* Negative, Null-, NaN-, Exponential- und überlange Beträge werden abgelehnt.
* Alle Buchungen laufen in SQLite-Transaktionen (Überweisung = beide Seiten oder keine); Abbuchungen sind bedingte
  `UPDATE`s (`balance >= betrag`), Kontostand kann nie negativ werden (zusätzlich `CHECK`-Constraint).
* Eine serialisierte DB-Verbindung → Befehls-Spam und parallele Aufrufe können kein Geld duplizieren (getestet).
* `/pay` hat einen Cooldown (`economy.pay-cooldown-ms`).
* Admin-Aktionen prüfen bei jedem Klick/Chat-Eingabe die Berechtigung `investsmp.admin`.
* Eigene Befehle als `console` filtern Argumente auf `A-Za-z0-9_.-`.

## Nachrichten & eigene Befehle

* `messages.yml`: alle Chat-Nachrichten und GUI-Texte. Jede Chat-Nachricht bekommt automatisch `[Invest SMP]` davor (`prefix`).
* `commands.yml`: eigene Befehle (Kurzbefehle, Info-Texte, Konsolen-Befehle) mit Rechten und Cooldown – siehe Kommentare in der Datei.

## Tests

`mvn test` führt aus:

* `EconomyServiceTest` – Konto, Überweisung, ungültige Beträge, Limits, Admin-Protokoll, Geld-Konstanz, parallele Befehle
* `InvestServiceTest` – Investieren, Summierung, Rendite über Zeit, Rundungsreste, Auto-/Manuell-Auszahlung, Pause, Offline-Cap
* `RestartTest` – Neustarts mit echter SQLite-Datei: Daten bleiben erhalten, keine Doppelauszahlung, Ausfallzeit, Protokoll nach Absturz

## Bekannte Grenzen

* Datenbankzugriffe laufen synchron im Server-Thread (kleine, lokale SQLite-Abfragen; für typische SMP-Größen unkritisch).
  `synchronous=NORMAL` im WAL-Modus: bei Stromausfall kann die allerletzte Transaktion fehlen, die Datei bleibt konsistent.
* Bei einer **fremden** Vault-Economy kann InvestSMP die Atomarität nicht garantieren (die Fremd-Economy hat kein Rollback);
  Fehlschläge werden bestmöglich zurückgebucht.
* Investiertes Kapital ist nicht auszahlbar (nicht Teil der Anforderungen) – Admins können Kontostände per Dashboard anpassen.


## Paper 26.2 Build ohne Maven auf deinem PC

Dieses Projekt enthält `.github/workflows/build.yml`. Wenn du den Projektordner in ein GitHub-Repository hochlädst, baut GitHub Actions das Plugin mit Java 25 und Maven und stellt `InvestSMP-1.0.0.jar` als Build-Artefakt bereit. Der Build ist erst dann bestätigt, wenn der Workflow erfolgreich durchgelaufen ist.
