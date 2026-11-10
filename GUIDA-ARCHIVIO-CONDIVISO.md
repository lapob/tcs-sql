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
| **Proprietario** | poteri illimitati: tutto quello che fa l'amministratore, più creare, modificare ed eliminare gli **altri amministratori e proprietari**. Deve sempre esserci almeno un proprietario attivo. |
| **Amministratore** | tutto: utenti (tranne i proprietari), backup, magazzino, postazione di sincronizzazione, contabilità |
| **Amministrazione** | vede **tutto**, contabilità compresa; **crea e modifica i clienti**, note e promemoria, esporta CSV e calendario |
| **Tecnico** | vede tutto **tranne la contabilità** (fatture, scadenze, saldi, RiBa e i relativi riepiloghi non gli arrivano proprio, nemmeno sul telefono); **crea e modifica i clienti**, scrive interventi e note |

Eliminare, ripristinare o unire clienti resta agli amministratori.

Con l'aggiornamento 2.7.7 il primo amministratore creato diventa automaticamente **Proprietario** (l'operazione è registrata nel registro sicurezza). Il ruolo "Ufficio" ora si chiama **Amministrazione**.

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

Dalla versione 2.7.7 vengono letti anche:
- **telefoni** dalla rubrica di Mastro (prima non venivano letti: con la 2.7.7 circa 4.900 clienti avranno il numero e il tasto *Chiama*);
- **dati fiscali dei registratori**: tutti i campi compilati della scheda macchina, i dati del QR code del misuratore e lo **storico delle verificazioni periodiche** (data, esito, tecnico, sigillo, importo, rapporto; le ultime 12 per cassa, con il totale);
- **sedi e destinazioni** dei clienti (indirizzo, telefono, orari, note; con link alla mappa);
- **RiBa** degli ultimi 3 anni (scadenza, importo, pagata o insoluta, banca e documento) — visibili solo a chi vede la contabilità.
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

Menu **Contabilità** (PC, browser; su Android in *Altro*), per proprietario, amministratori e amministrazione (non per i tecnici). I dati vengono da MastroSQL con *Importa da MastroSQL* (sola lettura: Mastro resta il programma di contabilità e le fatture si emettono sempre da Mastro).

- **Riepilogo**: fatturato dell'anno (imponibile, note di credito dedotte) con il confronto con lo stesso periodo dell'anno prima, importo da incassare, **scaduto**, pagamenti ai fornitori nei prossimi 30 giorni e grafico degli ultimi 12 mesi.
- **Da incassare**: scadenzario clienti. Apri una rata per segnarla come **Sollecitato**, **Promesso**, **Incassato** o **Contestato** con una nota, oppure per preparare l'**email di sollecito** già compilata. Stato e note sono condivisi con il team e **restano anche dopo ogni nuova importazione**.
- **Fatture**, **Da pagare** (fornitori) e **Clienti** (chi ha importi scaduti in cima).
- Nella **scheda cliente** c'è la sezione *Contabilità*: fatturato, da incassare, scaduto, saldo contabile di Mastro e rate aperte.
- Amministrazione e amministratori possono esportare **fatture** e **scadenze aperte** in CSV.

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

### Tenere sempre accesa la postazione

In **Impostazioni → Avvio automatico** scegli *Avvia con Windows*: Studio parte da solo all'accesso, ridotto a icona, e riprende subito Zucchetti e DTR-Service. Se la postazione non si fa sentire da più di 3 ore, in **Panoramica → Da fare oggi** compare un avviso per tutti.

## Come è organizzato Studio

- **Panoramica**: la scheda *Da fare oggi* mostra solo ciò che richiede attenzione (interventi aperti, promemoria, scadenze entro 7 giorni, casse con errori o silenziose, rate scadute, articoli sotto scorta); ogni riga apre la pagina giusta.
- **Clienti e lavoro**: Clienti, Interventi, Attività, Scadenze. **Scadenze** è un'agenda unica: licenze Zucchetti, verificazioni, abbonamenti, giornali elettronici, certificati RT e licenze RT-Utility, filtrabile per tipo e per periodo (7, 30, 90 giorni).
- **Parco installato**: Macchine, Registratori DTR, Licenze, Ordini.
- **Amministrazione**: Magazzino, Contabilità.
- **Sistema**: Sincronizzazione, Impostazioni, Utenti.

## 8. App Android

App **nativa** (Kotlin + Jetpack Compose, Material 3) — File: `D:\TopCashSQL\android\TopCash-Studio.apk`.

1. Copia l'APK sul telefono (o invialo via email / Drive) e aprilo. Se richiesto, consenti *Installa app sconosciute* per l'app da cui lo apri.
2. Al primo avvio l'indirizzo `192.168.1.220` è già inserito: premi **Collega**.
3. Accedi con il tuo utente (resta connesso).

**Registratori DTR** (in *Altro*: giornale elettronico, chiusure rimanenti, certificato, errori e interventi di ogni cassa, anche nella scheda cliente e nello scadenziario). Home con indicatori, Clienti con ricerca e scheda completa (chiama, email, mappa, preferiti, promemoria, note), Interventi (crea, modifica, stati e priorità, ricambi, rapportino con firma e invio PDF), Magazzino con scanner, Macchine e verificazioni, Scadenziario, Licenze, Ordini, tema chiaro/scuro. Si aggiorna in tempo reale e mostra gli ultimi dati anche senza rete.

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

## Macchine: marchi, modelli e portali

La pagina **Macchine** riconosce il marchio dal modello scritto in Mastro e divide i registratori in cinque **sezioni**: **DTR**, **Ital Retail**, **Axon Micrelec**, **Epson** e **Registratori di cassa** (tutti gli altri: RCH, Custom, Olivetti… il marchio resta scritto nel modello). A parte: casse automatiche Cashmatic, software e licenze, stampanti, bilance. La scheda cliente usa le stesse sezioni, su PC e su Android.

- **Edit, Ditron e derivati** (Smarty, Nina, Zero, Spot, Fly, Globe, Quadra, Flexy, Zip, Speedy, Mikropos, Seven, Buffetti One…) **non compaiono più** in Studio: restano solo in MastroSQL, che non viene toccato. Un modello scritto con "RCH" resta.
- **Sezioni**: una scheda per sezione con macchine attive, numero di modelli e, per DTR e Ital Retail, la **copertura del portale** (quante casse attive sono presenti sul portale e quante mancano). Clic su una sezione per filtrare, poi sui modelli.
- **Non nei portali**: le casse DTR e Ital Retail attive in Mastro che il portale non conosce, da controllare. Se un portale non è mai stato sincronizzato le casse risultano *da sincronizzare*, non mancanti.
- La colonna **Portale** e la scheda del registratore mostrano se la cassa è sul portale; nella scheda trovi anche i dati fiscali di Mastro e lo storico delle verificazioni.
- In fondo alle pagine **Registratori DTR** e **Casse Ital Retail** c'è l'elenco delle casse di Mastro che mancano su quel portale.

## Scheda cliente e lavoro di tutti i giorni

La scheda cliente (PC, browser e Android) si apre con i **contatti rapidi** (Chiama, Email, Mappa, Nuovo intervento) e quattro riquadri da leggere al volo: casse e dispositivi attivi, licenze Zucchetti con la prossima scadenza, interventi aperti, prossima verificazione. Sotto, le schede:

- **Riepilogo**: anagrafica, sedi e punti vendita, **note e promemoria** del team, dati Mastro (chiusi in un riquadro).
- **Dispositivi**: divisi per tipo — **registratori di cassa**, **casse automatiche Cashmatic**, **software e app**, stampanti, bilance. Prima le **attive**; l'interruttore *Mostra anche le dismesse* fa comparire le altre. Ogni cassa mostra marchio e modello, matricola, presenza sul portale, verificazione, giornale elettronico e abbonamento; un tocco apre la scheda completa. Poi i dati dei portali (DTR, Ital Retail) e PC e sistemi.
- **Licenze**: licenze Zucchetti in corso (le storiche in un riquadro a parte), con i **commenti** in un riquadro separato, e gli ordini web.
- **Interventi**: da fare, chiusi e storico Mastro.
- **Contabilità** (non per i tecnici).

**Interventi in un tocco** — in ogni intervento aperto, nella scheda cliente e nella pagina Interventi:
- **Firma**: il cliente firma sul telefono o sul tablet, il rapportino diventa definitivo;
- **Stampa**: il foglio dell'intervento da portare dal cliente, con il riquadro firma vuoto per firmare a penna;
- **Chiudi**: chiude l'intervento senza aprire il modulo;
- **Posticipa**: Domani, Dopodomani, Lunedì prossimo, Tra una settimana, due settimane, un mese o un giorno a scelta.

Nella pagina Interventi le viste **I miei** (assegnati a te) e **Oggi e in ritardo** mostrano subito cosa c'è da fare.

## Tecnico, condizioni e pagamenti di ogni cliente

Sotto il nome di ogni cliente c'è una riga che dice subito **chi lo segue, a che condizioni e se è in regola**:

- **Tecnico**: quello indicato in MastroSQL sulle macchine attive (se sono più tecnici vince quello con più macchine; gli altri si vedono passando sopra col mouse), altrimenti l'agente dell'anagrafica Mastro, altrimenti chi ha fatto l'ultimo intervento. In *Clienti* c'è il filtro **Tutti i tecnici** e il nome del tecnico si può cercare.
- **Condizioni** (dal campo *Servizio* delle macchine in MastroSQL): **Noleggio**, **Abbonamento** (anche canoni e abbonamenti telefonici), **Garanzia** (finché la garanzia della macchina è aperta), **Ore prepagate**, **Installazione**, **A chiamata** (assistenza a pagamento). Con più macchine scrive quante: *Noleggio · 3 macchine*; se c'è una scadenza la mostra.
- **Pagamenti** (solo per chi vede la contabilità): *Deve pagare € X, tutto scaduto* oppure *di cui € Y scaduti* (con le RiBa insolute), *Da pagare € X · scade tra 12 giorni*, *In regola · ultimo pagamento …*. Compare solo quando lo scadenziario del cliente è stato importato: senza dati non afferma nulla. Un tocco apre la scheda *Contabilità*.

Le scritte seguono i numeri: *1 licenza* / *3 licenze*, *scaduta 132 giorni fa* se è una sola, *tutte scadute*, *2 scadute, la prossima scade tra 40 giorni*, *Dispositivo attivo* / *Dispositivi attivi*.

### Clienti archiviati (automatico, nessuna cancellazione)

I clienti **senza** dispositivi attivi, licenze in corso, tecnico, pagamenti aperti, interventi negli ultimi 3 anni, ordini negli ultimi 2, note, preferito o dati dei portali vengono tolti dalla lista **Attivi** e messi in **Archiviati · N**. Non viene cancellato nulla: la ricerca li trova sempre, e appena ricevono una macchina, una licenza, un intervento o una nota tornano da soli tra gli attivi.

## Portali e accessi

La scheda **Portali e accessi** (in Panoramica quando manca un accesso, sempre in *Sincronizzazione*) mostra lo stato di MY Zucchetti, DTR-Service, Ital Retail e Axon Micrelec: verde collegato, giallo mai collegato, rosso accesso da rifare. Sulla postazione di sincronizzazione **Accedi di nuovo** apre la finestra del portale: accedi tu, Studio non salva le password e legge i dati in sola lettura.

## Fluidità automatica

Studio sceglie da solo il profilo grafico per ogni dispositivo (*Impostazioni → Prestazioni → Automatico*): misura la fluidità all'avvio e **continua a controllarla durante l'uso**. Passa da solo alla modalità leggera se lo schermo scatta, con il risparmio dati attivo, con la batteria sotto il 20% (finché non lo metti in carica) o se il sistema chiede trasparenze ridotte; rispetta "riduci movimento" e il contrasto elevato. Su touch i tocchi sono immediati e i pulsanti più grandi.

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
Da fuori si entra con **nome utente e password**, con protezione automatica contro i tentativi ripetuti; l'amministratore può **bloccare** in ogni momento un singolo dispositivo. In ufficio non cambia nulla.

### Due indirizzi

| Dove | Indirizzo | Come |
|---|---|---|
| In ufficio | `http://192.168.1.220:8470` (IP del server + porta) | rete locale, il più veloce |
| Fuori ufficio | `https://tcsql.it` | tunnel Cloudflare cifrato, nome utente e password |

Le app scelgono da sole: in ufficio usano l'IP, fuori passano a `https://tcsql.it`.

### Una volta sola (circa 10 minuti)

1. **Dominio su Cloudflare**: acquista `tcsql.it` (anche direttamente su [dash.cloudflare.com](https://dash.cloudflare.com) → *Domain Registration*). Se lo compri da un altro registrar, aggiungilo a Cloudflare (*Add a domain*, piano Free) e dal registrar imposta i **due nameserver** che Cloudflare ti indica.
2. **API token** (serve solo per la configurazione automatica): Cloudflare → icona profilo → *My Profile → API Tokens → Create Token → Custom token*, con i permessi
   - *Account* → **Cloudflare Tunnel** → **Edit**
   - *Zone* → **DNS** → **Edit**
   - *Zone* → **Zone Settings** → **Edit**
   - *Zone* → **Zone** → **Read**

   e in *Zone Resources* scegli *Include → Specific zone → tcsql.it*. Copia il token (lo vedi una volta sola). Finita la configurazione puoi eliminarlo.
3. Sul **PC server**, PowerShell *Esegui come amministratore*: prima `aggiorna-server.ps1`, poi
   ```
   powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\collega-esterno.ps1
   ```
   incolla l'API token quando lo chiede (non viene salvato). Lo script, da solo:
   - scarica `cloudflared` dal sito ufficiale e ne verifica la firma (`D:\TopCashSQL\.tools\cloudflared`);
   - crea (o riusa) il tunnel `topcash` e instrada `tcsql.it` e `www.tcsql.it` verso il server (porta del tunnel `8472`, raggiungibile solo da questo PC);
   - crea i record DNS (se ce ne sono già, ad esempio la pagina di parcheggio del registrar, chiede prima di sostituirli);
   - attiva **HTTPS obbligatorio**, **TLS 1.2 minimo** e **TLS 1.3**;
   - installa il connettore come servizio Windows e registra l'indirizzo pubblico sul server, così **tutte le app lo imparano da sole** al prossimo accesso in ufficio.

### Se il server è spento

Quando il PC server è spento, in riavvio o senza Internet, `https://tcsql.it` mostra una **pagina dedicata** ("Il server dell'ufficio non risponde") che riprova da sola ogni 20 secondi; le app ricevono un errore chiaro e, se le hai già aperte, mostrano gli ultimi dati salvati.
La pagina è il Worker Cloudflare `topcash-offline` (codice in `cloudflare\offline-worker.js`), sulla rotta `*tcsql.it/*` con **Fail open**: se il Worker avesse un problema, il traffico passa comunque al server. Non legge e non salva nessun dato (log disattivati).
Per modificarla: Cloudflare → *Workers & Pages → topcash-offline → Edit code*, incolla il file aggiornato e premi *Deploy*.

Altro dominio: `collega-esterno.ps1 -Dominio altro.it`. Se preferisci creare il tunnel a mano dal sito Cloudflare: `collega-esterno.ps1 -Manuale` e incolli il token del connettore.

### Sicurezza degli accessi e dispositivi

- Da fuori ufficio basta **nome utente e password**: nessun codice da approvare.
- **Protezione anti-tentativi** (solo da fuori ufficio): 5 password sbagliate in 15 minuti sospendono quell'utente da fuori per 15 minuti (il tempo raddoppia a ogni nuova sospensione, fino a 24 ore); 20 errori in un'ora dallo stesso indirizzo sospendono quell'indirizzo per un'ora. In ufficio si entra sempre, così nessuno può bloccare i colleghi dalla loro scrivania.
- **Utenti → Dispositivi** elenca ogni PC, telefono e browser che ha fatto accesso, con ultimo utente, ultimo accesso e indirizzo IP; **Blocca** chiude subito l'accesso da fuori a un telefono perso o di un ex collaboratore (che vedrà il proprio codice, es. `K7Q-4M2`), **Sblocca** lo riapre.
- **Utenti → Accessi e sicurezza** mostra gli accessi da fuori ufficio, le sospensioni per troppi tentativi e le modifiche a utenti e dispositivi; è visibile solo agli amministratori, come gli indirizzi IP dell'ufficio.

### Sui dispositivi

- **Scarica le app** (solo sul sito, ad esempio `https://tcsql.it`): pagina con l'app Android, Studio per Windows e le istruzioni per installare il sito come app su iPhone e browser; il dispositivo in uso viene riconosciuto e la sua app è proposta per prima.
- **Aggiornamenti più leggeri**: dalla 2.7.6 sito e app Android scaricano solo le parti dell'archivio cambiate (di solito pochi KB), non tutto l'archivio a ogni modifica.

- **Android e app Windows**: non serve fare nulla. Al primo accesso in ufficio dopo la configurazione imparano `https://tcsql.it` dal server; fuori ufficio passano da sole all'indirizzo pubblico. (Resta possibile scriverlo a mano: Android *Altro → Account e impostazioni → Cambia server → Indirizzo esterno*, Windows *Impostazioni → Questo PC → Indirizzo esterno*.)
- **Browser / iPhone**: apri `https://tcsql.it` e, se vuoi, *Aggiungi a schermata Home*. In *Impostazioni → Accesso dalla rete* trovi sia l'indirizzo dell'ufficio sia quello pubblico.

Per spegnere l'accesso da fuori: `collega-esterno.ps1 -Rimuovi`. Non esporre mai la porta 8470 direttamente su Internet.

## Disinstallare

```
powershell -ExecutionPolicy Bypass -File D:\TopCashSQL\disinstalla-server.ps1
```
I dati in `D:\TopCashSQL\data` non vengono toccati.
