# Top Cash Studio · Archivio condiviso

Un solo archivio per tutta l'azienda: tutti vedono gli stessi clienti, licenze, ordini, note e promemoria, aggiornati **in tempo reale**, da PC, tablet e telefono.

```
              MY Zucchetti
                   │  (lettura, ogni 6 ore)
                   ▼
   ┌──────────────────────────────┐        ┌───────────────────────────┐
   │ Postazione di sincronizzazione│ ─────▶ │  Server Top Cash (ufficio)│
   │ app Windows + accesso Zucchetti│ chiave │  database + backup        │
   └──────────────────────────────┘        └─────────────┬─────────────┘
                                                         │ aggiornamenti live
                         ┌──────────────┬────────────────┼──────────────┐
                         ▼              ▼                ▼              ▼
                     PC (app)      PC (browser)      Telefono        Tablet
```

## 1. Installa il server (una volta)

Sul PC dell'ufficio che resta sempre acceso (può essere lo stesso PC della postazione):

1. Apri **PowerShell come amministratore**.
2. Esegui:
   ```
   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\installa-server.ps1
   ```
   Lo script installa il servizio **Top Cash Studio Server**, che parte da solo con Windows e si riavvia in caso di errore. Apre la porta 8470 **solo sulla rete privata** dell'ufficio e alla fine mostra l'indirizzo da usare negli altri dispositivi (es. `http://192.168.1.20:8470`).
3. Apri `http://localhost:8470` e crea il tuo **account amministratore**.

> Vuoi solo provarlo prima? Fai doppio clic su `avvia-server-prova.cmd` (funziona solo su quel PC, finché la finestra resta aperta).

## 2. Aggiungi i colleghi

**Utenti** (nel menu) → *Nuovo utente*: nome, nome utente, ruolo e password iniziale (generata in automatico, da comunicare al collega).

| Ruolo | Può fare |
|---|---|
| Amministratore | tutto: utenti, backup, postazione di sincronizzazione |
| Ufficio | consulta, modifica note e promemoria, esporta CSV e calendario |
| Tecnico | consulta e scrive note, non esporta |

Se cambi un ruolo, disattivi un utente o reimposti una password, le sue sessioni aperte vengono chiuse subito.

## 3. Attiva la postazione di sincronizzazione

Sul PC che legge MY Zucchetti:

1. Apri **Top Cash Studio** → **Impostazioni** → *Archivio condiviso* → scrivi l'indirizzo del server → **Collega**.
2. Accedi con il tuo account amministratore.
3. **Utenti** → *Postazione di sincronizzazione* → **Crea chiave** → **Usa questo PC come postazione**.
4. **Sincronizzazione** → **Apri collegamento** → accedi a Zucchetti → **Sincronizza tutto**.

La chiave è salvata cifrata per l'utente Windows (DPAPI). Non è una password personale e si può revocare in ogni momento. Tieni il PC acceso con l'app aperta: i dati si aggiornano ogni 6 ore e tutti li ricevono subito.

## 4. Usa l'app da qualsiasi dispositivo

| Dispositivo | Come |
|---|---|
| PC Windows | App Top Cash Studio → Impostazioni → *Archivio condiviso* → indirizzo del server |
| Qualsiasi PC/Mac | Browser → `http://IP-DEL-SERVER:8470` |
| Telefono / tablet | Browser → indirizzo del server → *Aggiungi a schermata Home* |

## 5. Importa i clienti da MastroSQL

Da fare su un PC dove **MastroSQL è installato** (legge le impostazioni di Mastro in `C:\MastroSQL\RETE.ini`):

1. Apri **Top Cash Studio** (collegato al server) → **Impostazioni** → *Importa da MastroSQL* → **Leggi da MastroSQL**.
2. Controlla l'**anteprima**: numero di clienti, quanti hanno telefono ed email, macchine, interventi storici, esempio dei primi clienti.
3. Conferma con **Importa**.

Cosa viene importato: anagrafica completa (indirizzo, CAP, provincia, telefoni, email, PEC, zona, note e tutti gli altri campi compilati), **parco macchine** (modello, matricola, matricola fiscale, installazione, verificazione, abbonamento e importo) e **storico interventi**.
I clienti Mastro vengono collegati a quelli Zucchetti tramite partita IVA o codice fiscale, altrimenti tramite ragione sociale.

> **Sola lettura:** l'app esegue solo letture sul database di Mastro e non modifica nulla. Puoi ripetere l'importazione quando vuoi: i dati Mastro vengono sostituiti con l'ultima lettura, mentre interventi creati nell'app, note e promemoria restano invariati.

## 6. App Android

App **nativa** (Kotlin + Jetpack Compose, Material 3) — File: `D:\TopCashSQL\android\TopCash-Studio.apk`.

1. Copia l'APK sul telefono (o invialo via email / Drive) e aprilo. Se richiesto, consenti *Installa app sconosciute* per l'app da cui lo apri.
2. Al primo avvio l'indirizzo `192.168.1.220` è già inserito: premi **Collega**.
3. Accedi con il tuo utente (resta connesso).

Home con indicatori, Clienti con ricerca e scheda completa (chiama, email, mappa, preferiti, promemoria, note), Interventi (crea, modifica, stati e priorità), Macchine e verificazioni, Scadenziario, Licenze, Ordini, tema chiaro/scuro. Si aggiorna in tempo reale e mostra gli ultimi dati anche senza rete.

Per ricompilare: `powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\android\build.ps1` (aggiungi `-Install` per installarla sul telefono collegato).
**Conserva** `android\topcash-release.keystore` e `android\keystore-password.txt`: servono per gli aggiornamenti dell'app.

## Dove si trova tutto

Tutto è dentro `D:\TopCashSQL`:
`data` (database e backup) · `app` (programma Windows) · `server-app` / `server-update` (server) · `android` (app Android) · `src`, `server` (codice) · `.tools` (Gradle, Android SDK, librerie) · `.runtime` (cache e file temporanei, si può svuotare).
Fuori restano solo il servizio Windows, la regola del firewall e il collegamento sul Desktop, che puntano a questa cartella.

## Versioni del progetto (Git)

Il codice è versionato con Git portatile (`.tools\git`): ogni modifica può essere confrontata o annullata.
```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1                      (storico)
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1 -Salva "cosa è cambiato"
```
Lo storico contiene solo codice, script e guide: **database, backup, chiavi e password non vengono mai salvati**. Lo storico resta su questo PC (nessun invio online).

## Dopo questo aggiornamento

- L'app Windows ora tiene la sua cache dentro `D:\TopCashSQL\.runtime`: al primo avvio **accedi di nuovo** (server e, sulla postazione, MY Zucchetti). Le vecchie cache in `C:\Users\lapob\AppData\Local\TopCashStudio` non servono più e si possono eliminare.
- Esegui `aggiorna-server.ps1` per portare il server alla nuova versione (barre in vetro sfocato, file temporanei dentro `D:\TopCashSQL`).

## Aggiornare il server

Quando ricevi una nuova versione (cartella `server-update`), in PowerShell come amministratore:
```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\aggiorna-server.ps1
```
Il servizio si ferma per pochi secondi; dati, utenti e sessioni restano invariati.

## Sicurezza e dati

- Password salvate solo in forma cifrata (PBKDF2-SHA256, 210.000 iterazioni); accessi limitati contro i tentativi ripetuti.
- Protezione CSRF, sessioni che scadono dopo 14 giorni di inattività, intestazioni di sicurezza.
- **Backup automatico ogni giorno** in `D:\TopCashSQL\data\backups` (ultimi 30). Copia periodicamente la cartella su un disco esterno o su Google Drive.
- Cronologia: ogni sincronizzazione, importazione e modifica alle note riporta il nome dell'utente.

## Accesso da fuori ufficio (prossimo passo consigliato)

In ufficio il server usa `http://`, va bene sulla rete interna. Per usarlo **fuori ufficio**, e per installarlo sui telefoni con modalità offline, serve `https://`:

- **Tailscale** (consigliato, gratuito fino a 3 utenti / economico oltre): VPN privata con certificato HTTPS automatico. Nessuna porta aperta su Internet.
- **Server cloud** (es. Azure o un provider europeo, ~10-30 €/mese) con dominio `topcash.tuodominio.it`.

Non esporre mai la porta 8470 direttamente su Internet senza HTTPS.

## Disinstallare

```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\disinstalla-server.ps1
```
I dati in `D:\TopCashSQL\data` non vengono toccati.
