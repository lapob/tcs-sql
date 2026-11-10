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

Il tasto **indietro** (browser, gesto del telefono, tasto laterale del mouse) chiude prima la finestra o la scheda cliente aperta, poi torna alle pagine visitate: non fa più uscire dall'app.

## 5. Importa i clienti da MastroSQL

Da fare su un PC dove **MastroSQL è installato** (legge le impostazioni di Mastro in `C:\MastroSQL\RETE.ini`):

1. Apri **Top Cash Studio** (collegato al server) → **Impostazioni** → *Importa da MastroSQL* → **Leggi da MastroSQL**.
2. Controlla l'**anteprima**: numero di clienti, quanti hanno telefono ed email, macchine, interventi storici, esempio dei primi clienti.
3. Conferma con **Importa**.

Cosa viene importato: anagrafica completa (indirizzo, CAP, provincia, telefoni, email, PEC, zona, note e tutti gli altri campi compilati), **parco macchine** (modello, matricola, matricola fiscale, installazione, verificazione, abbonamento e importo) e **storico interventi**, oltre alla **contabilità** (fatture e note di credito, scadenze di incassi e pagamenti, saldi contabili: vedi sezione 6-bis).
I clienti Mastro vengono collegati a quelli Zucchetti tramite partita IVA o codice fiscale, altrimenti tramite ragione sociale: così ogni cliente compare **una sola volta**, con le sue **licenze Zucchetti** collegate. Se in Mastro lo stesso cliente è registrato due volte (stessa partita IVA), l'app li unisce in un'unica scheda.

> **Sola lettura:** l'app esegue solo letture sul database di Mastro e non modifica nulla. Puoi ripetere l'importazione quando vuoi: i dati Mastro vengono sostituiti con l'ultima lettura, mentre interventi creati nell'app, note e promemoria restano invariati.

## 6. Magazzino

Ricambi e consumabili in **sede** e su ogni **furgone** (un magazzino per tecnico). Menu **Magazzino** su PC, browser e Android, **visibile solo all'amministratore**: gli altri utenti non lo vedono e il server rifiuta ogni operazione di magazzino fatta da loro. I ricambi usati negli interventi continuano a scaricarsi in automatico.

1. **Magazzini e furgoni** → *Nuovo furgone*: nome e tecnico assegnato (scrivilo come negli interventi).
2. **Nuovo articolo** (o importa il catalogo da MastroSQL): codice, codice a barre, categoria, costo, prezzo, fornitore, *gestito a matricola* per i registratori.
3. **Carico** quando arriva la merce (con costo e numero DDT), **Trasferisci** sul furgone del tecnico.
4. Negli **interventi** aggiungi i *Ricambi usati*: vengono scaricati in automatico dal furgone del tecnico (se modifichi l'intervento non vengono scaricati due volte; se lo elimini tornano a magazzino).
5. Nella scheda di un articolo imposta **scorta minima e massima** per sede o furgone → la scheda **Riordino** propone cosa ordinare, raggruppato per fornitore (esportabile in CSV).
6. **Inventario**: conta la merce, scrivi le quantità reali e registra; vengono salvate solo le differenze.

Codici a barre: su PC funzionano i lettori USB/Bluetooth (scansiona nel campo di ricerca); su Android tocca l'icona di scansione per usare la fotocamera.
Ogni movimento resta nel registro con utente, data e note.

**Da MastroSQL** (Impostazioni → *Importa da MastroSQL*): vengono importati articoli, categorie e fornitori; spuntando *Allinea anche le giacenze* le quantità di Mastro diventano quelle di partenza (registrate come rettifiche, quindi tracciate). Da quel momento il magazzino operativo è Top Cash Studio.

## 6-bis. Contabilità

Menu **Contabilità** (PC, browser; su Android in *Altro*), per **tutti** gli utenti. I dati vengono da MastroSQL con *Importa da MastroSQL* (sola lettura: Mastro resta il programma di contabilità e le fatture si emettono sempre da Mastro).

- **Riepilogo**: fatturato dell'anno (imponibile, note di credito dedotte) con il confronto con lo stesso periodo dell'anno prima, importo da incassare, **scaduto**, pagamenti ai fornitori nei prossimi 30 giorni e grafico degli ultimi 12 mesi.
- **Da incassare**: scadenzario clienti. Apri una rata per segnarla come **Sollecitato**, **Promesso**, **Incassato** o **Contestato** con una nota, oppure per preparare l'**email di sollecito** già compilata. Stato e note sono condivisi con il team e **restano anche dopo ogni nuova importazione**.
- **Fatture**, **Da pagare** (fornitori) e **Clienti** (chi ha importi scaduti in cima).
- Nella **scheda cliente** c'è la sezione *Contabilità*: fatturato, da incassare, scaduto, saldo contabile di Mastro e rate aperte.
- Ufficio e amministratore possono esportare **fatture** e **scadenze aperte** in CSV.

## 7. Rapportino firmato dal cliente

A fine lavoro, nell'intervento premi **Firma e rapportino** (PC/browser) o *Fai firmare il rapportino al cliente* (Android):
1. scrivi le **ore lavorate** e il **nome di chi firma**;
2. il cliente firma nel riquadro con il dito, la penna o il mouse;
3. conferma: il rapportino riceve un **numero progressivo annuale** (es. `2026/0001`), l'intervento può essere chiuso in automatico e i ricambi vengono scaricati.

Subito dopo puoi:
- **Stampare**: su PC/browser si apre l'anteprima del PDF con il pulsante *Stampa* (scegli la stampante, il numero di copie, ecc.); su Android usa il servizio di stampa del telefono (stampanti Wi-Fi o *Salva come PDF*). Il bollettino stampato è identico al PDF.
- **Inviarlo per email al cliente** direttamente dall'indirizzo dell'azienda, con il PDF allegato: nella firma lascia la spunta *Invia il PDF firmato al cliente via email* (l'indirizzo è preso dalla scheda cliente e si può cambiare), oppure in seguito con **Invia al cliente** / *Email cliente*. Nell'intervento resta scritto a chi e quando è stato inviato.
- **Scaricare il PDF** o **condividerlo** con WhatsApp, Drive, ecc. (Android, *Condividi*).

Il PDF contiene logo, dati Top Cash, cliente, macchina, lavoro svolto, ricambi, ore e firma.
Un rapportino firmato **non è più modificabile**; solo ufficio e amministratore possono *Annullare la firma* (il numero resta lo stesso e l'operazione è registrata nella cronologia).

### Email rapportini (amministratore, una volta)

**Impostazioni → Email rapportini** (dal browser o dall'app collegata al server):
1. inserisci server SMTP, porta, sicurezza, utente e password della casella da cui partono i rapportini (es. Aruba: `smtps.aruba.it`, porta **465**, **SSL/TLS**; Gmail/Google Workspace: `smtp.gmail.com`, porta 587, STARTTLS con una *password per le app*);
2. mittente (es. `assistenza@topcash.it`) e, se vuoi, una **copia per l'ufficio** (Ccn) di ogni invio;
3. spunta *Attiva l'invio* → **Salva**, poi **Email di prova** per verificare.

La password è salvata **cifrata** sul server (Windows DPAPI): non è più visibile, non viaggia verso i dispositivi e non si può leggere da una copia del database. Finché l'invio non è attivo, i pulsanti email non compaiono.

## 7-bis. Registratori telematici DTR-Service

Studio copia in **sola lettura** l'elenco dei registratori di DTR-Service (RT-Utility): matricola, cliente, giornale elettronico (GE) e scadenza, firmware, ultima chiusura Z e ultima comunicazione.

1. Sulla **postazione di sincronizzazione** (o in archivio locale) apri **Registratori DTR → Collega DTR-Service**.
2. Nella finestra che si apre accedi tu con email e password DTR (Studio non le vede e non le salva; se Edge propone di ricordare la password, puoi accettare: il prossimo accesso sarà un clic).
3. La lettura parte da sola. Poi puoi **nascondere** la finestra: resta collegata.

Da quel momento, a ogni avvio dell'app Windows la sessione riprende in background: viene controllata ogni **15 minuti** (così DTR la rinnova) e i dati si aggiornano ogni **2 ore**. Se DTR chiede di nuovo l'accesso, la finestra si apre da sola e in Panoramica compare un avviso per tutti. L'elenco viene sostituito solo se la lettura è completa: un errore non cancella mai i dati precedenti.

Oltre all'elenco, Studio legge la **scheda di dettaglio** di ogni registratore (fino a 150 per giro, le più vecchie per prime: tutte si aggiornano più volte al giorno): intestatario e **partita IVA**, indirizzo di installazione, scadenza del **certificato**, ultima **verificazione periodica**, inizio del giornale elettronico, **chiusure rimanenti**, licenze RT-Utility, estensioni GE, ultimi **errori** e **interventi**, avvisi del portale.

Ogni cassa è collegata al cliente di Studio per **matricola** (macchina importata da Mastro), poi per **partita IVA**, infine per nome. Nella pagina: GE da rinnovare, casse con errori, casse **silenziose** (nessuna comunicazione da oltre 7 giorni), certificati e licenze RT-Utility in scadenza, firmware da aggiornare, casse non presenti in Mastro e macchine di Mastro che DTR non conosce. Clic su una riga: scheda completa del registratore, con *Apri cliente* e *Nuovo intervento*. I dati DTR compaiono anche in **Macchine** e nella scheda cliente.

## 8. App Android

App **nativa** (Kotlin + Jetpack Compose, Material 3) — File: `D:\TopCashSQL\android\TopCash-Studio.apk`.

1. Copia l'APK sul telefono (o invialo via email / Drive) e aprilo. Se richiesto, consenti *Installa app sconosciute* per l'app da cui lo apri.
2. Al primo avvio l'indirizzo `192.168.1.220` è già inserito: premi **Collega**.
3. Accedi con il tuo utente (resta connesso).

Home con indicatori, Clienti con ricerca e scheda completa (chiama, email, mappa, preferiti, promemoria, note), Interventi (crea, modifica, stati e priorità, ricambi, rapportino con firma e invio PDF), Magazzino con scanner, Macchine e verificazioni, Scadenziario, Licenze, Ordini, tema chiaro/scuro. Si aggiorna in tempo reale e mostra gli ultimi dati anche senza rete.

Per ricompilare: `powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\android\build.ps1` (aggiungi `-Install` per installarla sul telefono collegato).
**Conserva** `android\topcash-release.keystore` e `android\keystore-password.txt`: servono per gli aggiornamenti dell'app.

## Dove si trova tutto

Tutto è dentro `D:\TopCashSQL`:
`data` (database e backup) · `app` (programma Windows) · `server-app` / `server-update` (server) · `android` (app Android) · `src`, `server` (codice) · `.tools` (Gradle, Android SDK, librerie) · `.runtime` (cache e file temporanei, si può svuotare).
Fuori restano solo i servizi Windows (Top Cash e, se attivo, Cloudflare), la regola del firewall e il collegamento sul Desktop, che puntano a questa cartella.

## Versioni del progetto (Git)

Il codice è versionato con Git portatile (`.tools\git`): ogni modifica può essere confrontata o annullata.
```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1                      (storico)
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\versioni.ps1 -Salva "cosa è cambiato"
```
Lo storico contiene solo codice, script e guide: **database, backup, chiavi e password non vengono mai salvati**. Lo storico è salvato su questo PC e, quando pubblichi, anche nel repository **pubblico** GitHub `lapob/tcs-sql` (chiunque può leggere il codice: per questo database, chiavi e password non ci finiscono mai).

### Release su GitHub (download per i colleghi)

Le versioni pronte (APK Android, app Windows, aggiornamento server) si pubblicano come **Release** del repository:
```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\pubblica-release.ps1 -Versione 2.3.0
```
Lo script invia il codice, crea il pacchetto e la Release con i file da scaricare (controlla che nei pacchetti non ci siano database, chiavi o impostazioni di Mastro).
La prima volta accedi a GitHub con `D:\TopCashSQL\.tools\gh\bin\gh.exe auth login` (l'accesso è salvato in `.tools\gh-config`, fuori da Git).
Il repository è pubblico: i colleghi scaricano i file dalla pagina *Releases* (https://github.com/lapob/tcs-sql/releases) senza account. I pacchetti contengono solo i programmi: per usarli serve comunque un utente del server Top Cash.

## Dopo questo aggiornamento

- L'app Windows ora tiene la sua cache dentro `D:\TopCashSQL\.runtime`: al primo avvio **accedi di nuovo** (server e, sulla postazione, MY Zucchetti). Le vecchie cache in `C:\Users\lapob\AppData\Local\TopCashStudio` non servono più e si possono eliminare.
- Esegui `aggiorna-server.ps1` per portare il server alla nuova versione (contabilità, magazzino riservato all'amministratore, connessione da fuori ufficio, rapportino con firma, stampa ed email, backup su disco esterno).
- Poi, dal PC con MastroSQL, ripeti **Impostazioni → Importa da MastroSQL**: questa volta vengono lette anche fatture, scadenze e saldi.
- Installa sui telefoni la nuova `TopCash-Studio.apk` (versione 2.4.2: stampa ed email dei rapportini, trascina giù per aggiornare senza blocchi, numeri animati, gesto indietro con la pagina che si stacca come una scheda).
- Configura **Impostazioni → Email rapportini** (sopra, sezione 7) e controlla in **Impostazioni → Backup automatici** che la copia su `H:\TcsSqlOfflineBackups` risulti riuscita.

## Aggiornare il server

Quando ricevi una nuova versione (cartella `server-update`), in PowerShell come amministratore:
```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\aggiorna-server.ps1
```
Il servizio si ferma per pochi secondi; dati, utenti e sessioni restano invariati.

## Sicurezza e dati

- Password salvate solo in forma cifrata (PBKDF2-SHA256, 210.000 iterazioni); accessi limitati contro i tentativi ripetuti.
- Protezione CSRF, sessioni che scadono dopo 14 giorni di inattività, intestazioni di sicurezza.
- **Backup automatico ogni giorno**, verificato con il controllo di integrità di SQLite:
  - in `D:\TopCashSQL\data\backups` (ultimi 30);
  - **copia esterna** in `H:\TcsSqlOfflineBackups` (ultimi 120). La cartella si cambia in **Impostazioni → Backup automatici** (anche un NAS, es. `\\NAS\backup`); lì c'è anche **Backup adesso**.
  - Se la chiavetta H: non è collegata, il backup sul PC viene fatto comunque, la copia esterna viene ritentata **ogni ora** e la Panoramica dell'amministratore mostra un avviso finché non riesce.
  - I backup contengono i dati dei clienti: tieni la chiavetta in un posto sicuro (non lasciarla in auto o in giro).
- Cronologia: ogni sincronizzazione, importazione e modifica alle note riporta il nome dell'utente.

## Accesso da fuori ufficio (connessione privata)

Top Cash funziona anche fuori ufficio **senza installare altre app** su telefoni e PC: basta l'app Top Cash (o il browser).
La connessione passa da un **tunnel Cloudflare** cifrato (`https://`): sul router **non si apre nessuna porta** e il PC server non è visibile su Internet.
Da fuori entrano **solo i dispositivi che hai autorizzato** e, in più, serve sempre nome utente e password. In ufficio non cambia nulla.

### Una volta sola (circa 20 minuti)

1. **Dominio**: crea un account gratuito su [dash.cloudflare.com](https://dash.cloudflare.com) e registra un dominio (es. `topcash-app.it`, circa 10 €/anno, menu *Domain Registration*). Se ne hai già uno, aggiungilo a Cloudflare.
2. **Tunnel**: in Cloudflare apri *Zero Trust → Networks → Tunnels → Create a tunnel* → tipo **Cloudflared** → nome `topcash` → scegli *Windows* e **copia il comando** mostrato (contiene il token: non condividerlo).
3. **Indirizzo pubblico**: sempre nel tunnel, *Public Hostname → Add*: sottodominio `app`, il tuo dominio, *Service* **HTTP** e URL **`localhost:8472`**. Salva.
4. Sul **PC server**, come amministratore: prima `aggiorna-server.ps1`, poi
   ```
   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1
   ```
   incolla il comando/token copiato e scrivi l'indirizzo (es. `app.topcash-app.it`). Lo script scarica `cloudflared` (verificato) in `D:\TopCashSQL\.tools\cloudflared` e lo avvia come servizio.

### Autorizzare i dispositivi

- Da fuori ufficio, un telefono o PC nuovo mostra un **codice** (es. `K7Q-4M2`) e resta bloccato.
- L'amministratore apre **Utenti → Dispositivi** e preme **Autorizza** sul codice giusto: il dispositivo si sblocca da solo in pochi secondi.
- Lo stesso elenco mostra l'ultimo utente, l'ultimo accesso e l'indirizzo IP; **Blocca** chiude subito l'accesso da fuori a un telefono perso o di un ex collaboratore.
- I dispositivi degli **amministratori** che accedono in ufficio sono autorizzati in automatico.

### Sui dispositivi

- **Android**: *Altro → Account e impostazioni → Cambia server* e compila **Indirizzo esterno** (es. `app.topcash-app.it`). In ufficio l'app usa il Wi-Fi, fuori passa da sola all'indirizzo esterno.
- **App Windows** (portatili): *Impostazioni → Questo PC → Indirizzo esterno*, stesso comportamento automatico.
- **Browser / iPhone**: apri `https://app.topcash-app.it` e, se vuoi, *Aggiungi a schermata Home*.

Per spegnere l'accesso da fuori: `collega-esterno.ps1 -Rimuovi`. Non esporre mai la porta 8470 direttamente su Internet.

## Disinstallare

```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\disinstalla-server.ps1
```
I dati in `D:\TopCashSQL\data` non vengono toccati.
