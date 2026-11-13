using System.Text.Json;
using TopCash.Licenze;

namespace TopCash.Server;

/// Which portals the server keeps itself (no Windows app needed). Changed only by the owner.
public sealed record PortalServerConfig(List<string> Enabled, string UpdatedAt = "", string UpdatedBy = "");

/// What the owner sees for each portal kept by the server.
public sealed record PortalView(string Id, string Name, bool Enabled, bool Browser, bool Connected, bool Running, string Status,
    string LastSync, string Error, string Url, string Every, bool Blocked = false);

/// The portals kept by the server: MY Zucchetti (licences, customers, web orders), DTR-Service, Ital Retail and RCH XDealer.
/// Each one runs in its own headless browser with its own private profile; the owner signs in once through the live
/// picture of the page (sito, Windows, Android) and the server then keeps the session and reads the lists on its own,
/// read-only, exactly like the Windows app did. Passwords are typed by the owner straight into the portal page: the
/// server never stores or logs them.
public sealed class Portals
{
    readonly Store store; readonly Hub hub;
    public readonly PortalRunner[] All;
    public Portals(Store store, Hub hub)
    {
        this.store = store; this.hub = hub;
        All = [new ZucchettiRunner(store, hub), new DtrRunner(store, hub), new ItalRunner(store, hub), new RchRunner(store, hub)];
    }
    public PortalRunner? Get(string id) => All.FirstOrDefault(p => p.Id == id);
    /// Every portal is kept by the server unless the owner took one off (the choice, once made, is saved and respected).
    public PortalServerConfig Config() => store.Setting<PortalServerConfig>("portals.server") ?? new PortalServerConfig([.. All.Select(p => p.Id)]);
    public bool Hosted(string id) => Config().Enabled.Contains(id);
    /// Kept by the server AND signed in: only then the office station steps aside, so no portal is ever left unread
    /// while it waits for the owner's sign-in.
    public bool Ready(string id) => Hosted(id) && Get(id)?.Connected == true;
    /// The Windows app's sync station must not send what the server now reads itself (two sessions could log each other out).
    public bool HostsKind(string kind) => All.Any(p => p.Kinds.Contains(kind) && Ready(p.Id));
    public List<string> HostedIds() => Config().Enabled.Where(id => Get(id) != null).ToList();
    public List<string> ReadyIds() => All.Where(p => Ready(p.Id)).Select(p => p.Id).ToList();

    public void Begin() { foreach (var p in All) if (Hosted(p.Id)) p.Enable(); }

    public async Task SetEnabled(string id, bool on, string who)
    {
        var p = Get(id) ?? throw new InvalidDataException("Portale sconosciuto.");
        var c = Config(); var list = c.Enabled.Where(x => x != id).ToList(); if (on) list.Add(id);
        store.SetSetting("portals.server", new PortalServerConfig(list, DateTimeOffset.Now.ToString("O"), who));
        if (on) { p.Enable(); store.Log("portal", $"{who} ha spostato {p.Name} sul server: dopo l'accesso la postazione dell'ufficio non serve più per questo portale."); }
        else { await p.Disable(); store.Log("portal", $"{who} ha tolto {p.Name} dal server."); }
        hub.Changed();
    }

    public List<PortalView> Views() => All.Select(p => p.View(Hosted(p.Id))).ToList();
    public async Task StopAll() { foreach (var p in All) await p.Disable(false); }
}

/// One portal kept by the server: browser life, session check, keep-alive, scheduled read, live picture and input.
public abstract class PortalRunner
{
    protected readonly Store store; protected readonly Hub hub;
    protected PortalRunner(Store store, Hub hub) { this.store = store; this.hub = hub; }
    public abstract string Id { get; }
    public abstract string Name { get; }
    protected abstract string StartUrl { get; }
    /// Status record name for the portals that have one ("dtrstatus" …), null for Zucchetti.
    protected virtual string? StatusKind => null;
    protected virtual string Product => Id.ToUpperInvariant();
    /// Data kinds this portal writes (used to keep the office station from sending the same lists).
    public abstract string[] Kinds { get; }
    protected abstract TimeSpan Every { get; }
    protected abstract bool Allowed(string url);
    /// Signed in, judged from the page on screen (RCH confirms with a real call).
    protected abstract Task<bool> SignedIn(CancellationToken ct);
    protected abstract Task<string> Read(CancellationToken ct);
    protected virtual string LastSyncStored() => store.Setting<string>("portals." + Id + ".lastSync") ?? "";
    protected virtual void LastSyncSave(string at) => store.SetSetting("portals." + Id + ".lastSync", at);

    /// Private browser profile of this portal, next to the archive (data\portali\<id>): never in backups, never shared.
    protected string ProfileDir { get { var d = Path.Combine(Path.GetDirectoryName(store.DatabasePath)!, "portali", Id); Directory.CreateDirectory(d); return d; } }
    HeadlessBrowser? browser; protected PortalPage? page;
    readonly SemaphoreSlim life = new(1, 1);
    CancellationTokenSource? loop, sync;
    bool enabled, running, authenticated, everAuthenticated, noSandbox;
    /// The portal put a Cloudflare robot check in front of its sign-in: a browser without a screen never passes it, so the
    /// server stops trying (one new look a day, in case the portal drops it) and the office station keeps reading this portal.
    DateTime blockedAt = DateTime.MinValue;
    public bool Blocked => blockedAt != DateTime.MinValue;
    const string BlockedText = "Il portale chiede la verifica anti-robot di Cloudflare, che il server non può superare: lo legge la postazione in ufficio.";
    string status = "", error = "";
    DateTime lastKeep = DateTime.MinValue, lastView = DateTime.MinValue, lastStartFail = DateTime.MinValue, lastSyncTry = DateTime.MinValue;
    int startFails;

    public bool Connected => enabled && authenticated;
    public PortalView View(bool hostedFlag) => new(Id, Name, hostedFlag, browser?.Alive == true, authenticated, running,
        status, LastSyncStored(), error, browser?.Alive == true ? Host(page?.Url) : "", Every.TotalHours >= 1 ? $"ogni {Every.TotalHours:0} ore" : $"ogni {Every.TotalMinutes:0} minuti", Blocked && !authenticated);
    static string Host(string? url) => Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.Host : "";

    public void Enable()
    {
        if (enabled) return;
        enabled = true; loop = new CancellationTokenSource();
        var ct = loop.Token;
        _ = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try { await Tick(ct); } catch (OperationCanceledException) { } catch (Exception ex) { Fail(ex.Message); }
                try { await Task.Delay(TimeSpan.FromSeconds(20), ct); } catch (OperationCanceledException) { }
            }
        });
    }

    public async Task Disable(bool log = true)
    {
        enabled = false; loop?.Cancel(); sync?.Cancel();
        await life.WaitAsync();
        try { await CloseBrowser(); } finally { life.Release(); }
        authenticated = false; status = log ? "Spento sul server" : status;
    }

    async Task CloseBrowser()
    {
        if (page != null) { try { await page.KeepSession(); } catch { } }
        if (browser != null) { await browser.DisposeAsync(); browser = null; page = null; }
    }

    async Task Ensure(CancellationToken ct)
    {
        if (browser?.Alive == true && page != null) return;
        if (startFails > 0 && DateTime.Now - lastStartFail < TimeSpan.FromMinutes(Math.Min(30, 2 * startFails))) throw new InvalidOperationException(error);
        await life.WaitAsync(ct);
        try
        {
            if (browser?.Alive == true && page != null) return;
            await CloseBrowser();
            var b = new HeadlessBrowser(ProfileDir);
            try { await b.Start(ct, noSandbox); }
            catch when (!noSandbox)
            {
                // some service accounts cannot start the browser sandbox: one retry without it (the browser only opens this portal)
                await b.DisposeAsync(); noSandbox = true; b = new HeadlessBrowser(ProfileDir); await b.Start(ct, true);
            }
            browser = b;
            page = await PortalPage.Open(b, Allowed, ct);
            page.UrlChanged = () => { _ = Task.Run(async () => { await Task.Delay(1500); await Detect(CancellationToken.None); }); };
            startFails = 0; error = "";
            status = "Apertura del portale…";
            await page.Navigate(StartUrl, ct);
            await Task.Delay(2500, ct);
            await Detect(ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            startFails++; lastStartFail = DateTime.Now;
            error = "Browser del server non avviato: " + ex.Message;
            if (startFails == 1) Log("error", Name + ": " + error);
            await CloseBrowser();
            throw new InvalidOperationException(error);
        }
        finally { life.Release(); }
    }

    /// The owner is looking at (or typing into) the page: no automatic navigation for a few minutes.
    bool Watching => DateTime.Now - lastView < TimeSpan.FromMinutes(3);

    async Task Tick(CancellationToken ct)
    {
        if (!enabled) return;
        // blocked by the robot check: the browser stays closed, one new look a day (or when the owner opens the page)
        if (Blocked && !Watching)
        {
            if (DateTime.Now - blockedAt < TimeSpan.FromHours(24)) { if (browser != null) { await life.WaitAsync(ct); try { await CloseBrowser(); } finally { life.Release(); } } return; }
            blockedAt = DateTime.MinValue;
        }
        await Ensure(ct);
        if (running || Watching) return;
        // the owner closed the live view: back to the desktop size the readings expect
        if (page != null && page.ViewWidth != PortalPage.Width) { try { await page.SetViewport(PortalPage.Width, PortalPage.Height, ct); } catch { } }
        if (DateTime.Now - lastKeep > TimeSpan.FromMinutes(15))
        {
            lastKeep = DateTime.Now;
            // reloading the start page lets the portal renew its session; a move to the sign-in page means a new sign-in is needed
            try { await page!.Navigate(StartUrl, ct); await Task.Delay(6000, ct); } catch (Exception ex) when (ex is not OperationCanceledException) { }
            await Detect(ct);
            if (authenticated) { try { await page!.KeepSession(ct); } catch { } Heartbeat(true, "Sessione attiva"); }
        }
        if (authenticated && Due() && DateTime.Now - lastSyncTry > TimeSpan.FromMinutes(10)) await SyncNow();
    }

    bool Due() => !DateTimeOffset.TryParse(LastSyncStored(), out var at) || DateTimeOffset.Now - at > Every;

    readonly SemaphoreSlim detectGate = new(1, 1);
    async Task Detect(CancellationToken ct)
    {
        if (page == null || running) return;
        if (!await detectGate.WaitAsync(0)) return;
        try
        {
            bool now;
            try { now = await SignedIn(ct); } catch { return; }
            if (now) { if (Blocked) { blockedAt = DateTime.MinValue; hub.Changed(); } }
            else if (await CloudflareWall(ct))
            {
                var first = !Blocked;
                blockedAt = DateTime.Now; authenticated = false; status = BlockedText;
                // no status written: the office station is the one reading this portal, its state stays on screen
                if (first) { Log("portal", Name + ": " + BlockedText); hub.Changed(); }
                return;
            }
            if (now == authenticated) { if (!now) status = "Accesso richiesto: apri il portale e accedi"; return; }
            authenticated = now;
            if (now)
            {
                everAuthenticated = true; error = "";
                status = "Collegato · lettura " + View(true).Every;
                Heartbeat(true, "Collegato");
                try { await page.KeepSession(ct); } catch { }
            }
            else
            {
                status = "Accesso richiesto: apri il portale e accedi";
                // only a session the server had and lost is reported: before its first sign-in the station's state is the true one
                if (everAuthenticated) Heartbeat(false, "Accesso richiesto");
            }
            hub.Changed();
        }
        finally { detectGate.Release(); }
    }

    /// A Cloudflare challenge (Turnstile box or the "just a moment" page) on the page shown: read only, nothing is clicked.
    async Task<bool> CloudflareWall(CancellationToken ct)
    {
        const string js = "(()=>{const q=s=>document.querySelector(s);return !!(q('iframe[src*=\"challenges.cloudflare.com\"]')||q('script[src*=\"challenges.cloudflare.com\"]')||q('.cf-turnstile')||q('input[name=\"cf-turnstile-response\"]')||q('#challenge-form')||q('#cf-challenge-running')||/^(just a moment|un momento)/i.test(document.title))})()";
        try { return (await Js(js, ct)).Trim() == "true"; } catch { return false; }
    }

    /// Reads the portal now (owner's "Aggiorna ora", or the schedule). Never two reads at once.
    public async Task<string> SyncNow()
    {
        if (running) return "Lettura già in corso.";
        if (!enabled) throw new InvalidOperationException(Name + " non è attivo sul server.");
        await Ensure(CancellationToken.None);
        if (!authenticated) { await Detect(CancellationToken.None); if (!authenticated) throw new InvalidOperationException("Prima accedi al portale."); }
        running = true; lastSyncTry = DateTime.Now; sync = new CancellationTokenSource(TimeSpan.FromHours(2)); var ct = sync.Token;
        try { await page!.SetViewport(PortalPage.Width, PortalPage.Height, ct); } catch { }
        SetStatus("Lettura in corso…");
        try
        {
            var msg = await Read(ct);
            LastSyncSave(DateTimeOffset.Now.ToString("O"));
            if (!ServerRead) store.SetSetting("portals." + Id + ".serverRead", true);
            SetStatus(msg + " · prossima lettura " + View(true).Every);
            try { await page!.KeepSession(ct); } catch { }
            return msg;
        }
        catch (OperationCanceledException) { SetStatus("Lettura interrotta: dati precedenti conservati."); throw new InvalidOperationException("Lettura interrotta."); }
        catch (UnauthorizedAccessException ex) { authenticated = false; SetStatus(ex.Message); Heartbeat(false, "Accesso richiesto"); throw new InvalidOperationException(ex.Message); }
        catch (Exception ex) { SetStatus(ex.Message); Log("error", Name + ": " + ex.Message); throw new InvalidOperationException(ex.Message); }
        finally
        {
            running = false; sync?.Dispose(); sync = null; hub.Changed();
            if (Progress) hub.SetProgress(false, authenticated, status);
            try { if (page != null && authenticated) await page.Navigate(StartUrl, CancellationToken.None); } catch { }
            _ = Detect(CancellationToken.None);
        }
    }
    public void StopSync() => sync?.Cancel();

    /// Zucchetti shows its progress in the shared "sincronizzazione" strip like the station did.
    protected virtual bool Progress => false;
    protected void SetStatus(string text)
    {
        status = text.Length > 300 ? text[..300] : text;
        if (Progress) hub.SetProgress(running, authenticated, status);
    }
    protected void Fail(string message) { error = message; status = message; }
    protected void Log(string kind, string message) { store.Log(kind, message.Length > 2000 ? message[..2000] : message); hub.Changed(); }
    protected string Apply(string kind, string scope, List<string> headers, List<List<string>> rows) =>
        store.ApplyPortalList(kind, Product, scope, headers, rows, DateTimeOffset.Now.ToString("O"), Name + " · server");
    (bool C, string M, int T) lastBeat; DateTime lastBeatAt;
    /// The server has completed at least one read of this portal (saved): only from then on it is the one that reads it.
    bool ServerRead => store.Setting<bool?>("portals." + Id + ".serverRead") == true;
    protected void Heartbeat(bool connected, string message, int total = 0)
    {
        if (StatusKind == null) return;
        // a portal the server never managed to read is still the office PC's: its "signed out" would cover the PC's real state
        if (!connected && !ServerRead) return;
        // the same status again within the hour changes nothing on screen: no write, no update sent to the pages
        if (lastBeat == (connected, message, total) && DateTime.Now - lastBeatAt < TimeSpan.FromHours(1)) return;
        lastBeat = (connected, message, total); lastBeatAt = DateTime.Now;
        try { store.ApplyPortalList(StatusKind, Product, "Stato " + Name, [connected ? "1" : "0", message, total.ToString()], [], DateTimeOffset.Now.ToString("O"), Name + " · server"); hub.Changed(); } catch { }
    }
    protected void Expired(string message) => throw new UnauthorizedAccessException(message);

    // ── live picture and input for the owner's sign-in
    public async Task<(byte[] Jpeg, string Url)> Picture(int w = 0, int h = 0)
    {
        lastView = DateTime.Now;
        if (!enabled) throw new InvalidOperationException(Name + " non è attivo sul server.");
        await Ensure(CancellationToken.None);
        // from a phone the portal is drawn at phone size (its own mobile page), so it is readable and easy to tap
        if (!running) await page!.SetViewport(w > 0 ? w : PortalPage.Width, h > 0 ? h : PortalPage.Height);
        return (await page!.Picture(), page.Url);
    }
    public async Task Input(PortalInput i)
    {
        lastView = DateTime.Now;
        if (!enabled || page == null) throw new InvalidOperationException(Name + " non è attivo sul server.");
        var pw = page.ViewWidth; var ph = page.ViewHeight;
        double X(double v) => Math.Clamp(v, 0, 1) * pw;
        double Y(double v) => Math.Clamp(v, 0, 1) * ph;
        switch (i.Kind)
        {
            case "click": await page.Click(X(i.X), Y(i.Y)); break;
            case "scroll": await page.Scroll(X(i.X), Y(i.Y), Math.Clamp(i.Dy, -3000, 3000)); break;
            case "text": if (!string.IsNullOrEmpty(i.Text) && i.Text.Length <= 500) await page.Type(i.Text); break;
            case "key": await page.Key(i.Text ?? ""); break;
            case "back": await page.Back(); break;
            case "reload": await page.Navigate(page.Url.StartsWith("http") ? page.Url : StartUrl, CancellationToken.None); break;
            case "home": await page.Navigate(StartUrl, CancellationToken.None); break;
            case "done": lastView = DateTime.MinValue; await Detect(CancellationToken.None); if (authenticated && Due()) _ = Task.Run(async () => { try { await SyncNow(); } catch { } }); break;
        }
    }

    /// Signs out for good: closes the browser and deletes this portal's private profile (cookies, saved sign-in).
    public async Task Forget()
    {
        var was = enabled;
        await Disable(false);
        try { Directory.Delete(ProfileDir, true); } catch { }
        everAuthenticated = false; status = "Profilo cancellato: serve un nuovo accesso"; error = "";
        if (was) Enable();
    }

    // ── helpers shared by the readers
    protected Task<string> Js(string script, CancellationToken ct) => page!.Eval(script, ct);
    protected async Task Go(string url, CancellationToken ct)
    {
        if (!await page!.Navigate(url, ct)) throw new IOException(Name + " non ha aperto la pagina richiesta.");
    }
    protected Dictionary<string, DateTime> LoadAges()
    {
        try { return new(JsonSerializer.Deserialize<Dictionary<string, DateTime>>(File.ReadAllText(Path.Combine(ProfileDir, "details-read.json"))) ?? [], StringComparer.OrdinalIgnoreCase); }
        catch { return new(StringComparer.OrdinalIgnoreCase); }
    }
    protected void SaveAges(Dictionary<string, DateTime> ages) { try { File.WriteAllText(Path.Combine(ProfileDir, "details-read.json"), JsonSerializer.Serialize(ages)); } catch { } }
    protected static bool HostIs(string? url, params string[] hosts) => Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https" && hosts.Contains(u.Host);
    protected static bool Https(string url) => url == "about:blank" || Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https";
}

public sealed record PortalInput(string Kind, double X = 0, double Y = 0, double Dy = 0, string? Text = null);

// ───────────────────────── MY Zucchetti: licences, customer registry and web orders (6 product families)
public sealed class ZucchettiRunner(Store store, Hub hub) : PortalRunner(store, hub)
{
    const string Base = "https://www.zucchetti.it/myzucchettigroup/jsp/";
    public override string Id => "zucchetti";
    public override string Name => "MY Zucchetti";
    protected override string StartUrl => Base + "home.jsp";
    protected override TimeSpan Every => TimeSpan.FromHours(6);
    public override string[] Kinds => ["licenses", "clients", "orders"];
    protected override bool Progress => true;
    // Same rule as the Windows app: the customer registry is on the cancellation page, never ordering/renewal/blocking flows.
    protected override bool Allowed(string url) => Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https" && new[] { "www.zucchetti.it", "sso.zucchetti.it" }.Contains(u.Host)
        && (u.AbsolutePath.EndsWith("/sior_wdisdette.jsp", StringComparison.OrdinalIgnoreCase) || !new[] { "ptblocco", "rinnova", "disdett", "carrello", "checkout" }.Any(s => u.AbsolutePath.Contains(s, StringComparison.OrdinalIgnoreCase)));
    protected override Task<bool> SignedIn(CancellationToken ct) =>
        Task.FromResult(Uri.TryCreate(page?.Url, UriKind.Absolute, out var u) && u.Host == "www.zucchetti.it" && !u.AbsolutePath.Contains("login", StringComparison.OrdinalIgnoreCase));

    static readonly (string Product, string Cat, string Label)[] Families = [("ILCONTO", "IC", "Il Conto"), ("ZMENU", "ZM", "ZMenu"), ("ILCONTOAN", "ICN", "Il Conto noleggio"), ("POSSISTEMI", "PS", "Pos Sistemi"), ("SELLBY", "SL", "Sellby"), ("TILBY", "TI", "Tilby")];
    enum Kind { Licenses, Clients, Orders }

    protected override async Task<string> Read(CancellationToken ct)
    {
        var steps = new List<(Kind Kind, string Product, string Cat, bool Expired, string Scope)>();
        foreach (var f in Families) { steps.Add((Kind.Licenses, f.Product, f.Cat, false, f.Product + "-attive")); steps.Add((Kind.Licenses, f.Product, f.Cat, true, f.Product + "-scadute")); }
        foreach (var f in Families) steps.Add((Kind.Clients, f.Product, f.Cat, false, "Anagrafica " + f.Label));
        foreach (var f in Families) steps.Add((Kind.Orders, f.Product, f.Cat, false, "Ordini " + f.Label));
        int ok = 0, licenses = 0, clients = 0, orders = 0;
        foreach (var step in steps)
        {
            ct.ThrowIfCancellationRequested(); SetStatus($"[{ok + 1}/{steps.Count}] Lettura {step.Scope}…");
            try
            {
                if (step.Kind == Kind.Licenses)
                {
                    var pg = step.Expired ? "sior_wlar_storico_chiavicatlisParScad.jsp" : "sior_wlar_storico_chiavicatlisPartner.jsp";
                    var (h, rows) = await ReadList(Base + pg + "?w_MOCODPROD=" + step.Product + "&m_cWindowName=main&m_cWindowName=main", "_Grid5", true, step.Scope, ct);
                    ApplyAs("licenses", step.Product, step.Scope, h, rows); licenses += rows.Count;
                }
                else if (step.Kind == Kind.Clients)
                {
                    var (h, rows) = await ReadList(Base + "sior_wdisdette.jsp?w_LISTCATID=" + step.Cat + "&w_LISTCATTYPE=ART&m_cWindowName=main", "_Grid", false, step.Scope, ct);
                    ApplyAs("clients", step.Product, step.Scope, h, rows); clients += rows.Count;
                }
                else
                {
                    var (h, rows) = await ReadList(Base + "sior_view_orders_portlet.jsp?listCatType=ART&listCatId=" + step.Cat, "_Grid0", false, step.Scope, ct);
                    ApplyAs("orders", step.Product, step.Scope, h, rows); orders += rows.Count;
                }
                ok++; hub.Changed();
            }
            catch (OperationCanceledException) { throw; }
            catch (UnauthorizedAccessException) { throw; }
            catch (Exception ex) { Log("error", step.Scope + ": " + ex.Message); }
        }
        var msg = $"Letti {ok}/{steps.Count} elenchi · {licenses} licenze · {clients} anagrafiche · {orders} ordini";
        Log("portal", $"Sincronizzazione dal server conclusa: {ok}/{steps.Count} elenchi · {licenses} licenze · {clients} schede anagrafiche · {orders} ordini. Nessun ordine, rinnovo, blocco o disdetta modificato.");
        return msg;
    }
    void ApplyAs(string kind, string product, string scope, List<string> headers, List<List<string>> rows) =>
        store.ApplyPortalList(kind, product, scope, headers, rows, DateTimeOffset.Now.ToString("O"), "Zucchetti · server");

    async Task<(List<string> Headers, List<List<string>> Rows)> ReadList(string url, string grid, bool sortBySerial, string label, CancellationToken ct)
    {
        await Go(url, ct);
        if (!Uri.TryCreate(page!.Url, UriKind.Absolute, out var u) || u.Host != "www.zucchetti.it") Expired("Accesso Zucchetti richiesto.");
        var initial = await WaitGrid(grid, "", ct);
        if (initial.NoData) return (initial.Headers, []);
        if (sortBySerial && initial.Rows.Count > 0)
        {
            // a unique serial order prevents overlapping pages when many licences share an expiry date
            await Js(G(PortalJs.ZucSortHeader, grid), ct);
            bool sorted = false;
            for (int retry = 0; retry < 10; retry++) { if (await Js(PortalJs.ZucSortAsc, ct) == "true") { sorted = true; break; } await Task.Delay(200, ct); }
            if (!sorted) throw new InvalidDataException("Impossibile impostare un ordinamento stabile per matricola.");
            await Task.Delay(700, ct);
        }
        var all = new List<List<string>>(); List<string>? headers = null; string last = "";
        for (int p = 0; p < 1000; p++)
        {
            var snap = await WaitGrid(grid, last, ct); headers ??= snap.Headers;
            if (snap.NoData) return (headers, all);
            if (snap.Rows.Count == 0) throw new InvalidDataException("Griglia senza dati e senza conferma di elenco vuoto.");
            var signature = JsonSerializer.Serialize(snap.Rows); if (signature == last) throw new InvalidDataException("La pagina non è avanzata. Dati precedenti conservati.");
            last = signature; all.AddRange(snap.Rows);
            SetStatus($"Lettura {label}: {all.Count} righe…");
            if (!snap.Next) return (headers, all);
            // the only automated portal action is the visible "Pagina successiva" control
            if (await Js(G(PortalJs.ZucNext, grid), ct) != "true") throw new InvalidDataException("Paginazione non disponibile.");
        }
        throw new InvalidDataException("Limite di pagine raggiunto: nessun dato parziale applicato.");
    }
    static string G(string script, string grid) => script.Replace("__GRID__", "[...document.querySelectorAll('table[id]')].find(t=>t.id.endsWith('" + grid + "'))");
    sealed record GridSnapshot(List<string> Headers, List<List<string>> Rows, bool Next, bool NoData);
    async Task<GridSnapshot> WaitGrid(string grid, string previous, CancellationToken ct)
    {
        var script = G(PortalJs.ZucGrid, grid);
        for (int attempt = 0; attempt < 50; attempt++)
        {
            ct.ThrowIfCancellationRequested(); var raw = await Js(script, ct);
            if (raw != "null")
            {
                var g = JsonSerializer.Deserialize<GridSnapshot>(raw);
                if (g != null && (g.NoData || g.Rows.Any(r => r.Any(s => !string.IsNullOrWhiteSpace(s))) && JsonSerializer.Serialize(g.Rows) != previous)) return g;
            }
            await Task.Delay(400, ct);
        }
        throw new TimeoutException("Il portale non ha caricato la griglia o la pagina successiva.");
    }
}

// ───────────────────────── DTR-Service: telematic cash registers and their detail pages
public sealed class DtrRunner(Store store, Hub hub) : PortalRunner(store, hub)
{
    const string Origin = "https://www.dtr-service.it";
    const string ListUrl = Origin + "/rt_utility/cashregisters";
    public override string Id => "dtr";
    public override string Name => "DTR-Service";
    protected override string StartUrl => ListUrl;
    protected override string? StatusKind => "dtrstatus";
    protected override string Product => "DTR";
    protected override TimeSpan Every => TimeSpan.FromHours(2);
    public override string[] Kinds => ["dtr", "dtrdetail", "dtrstatus"];
    protected override bool Allowed(string url) => url == "about:blank" || HostIs(url, "www.dtr-service.it", "dtr-service.it");
    protected override string LastSyncStored() => store.DtrState().LastSync;
    protected override void LastSyncSave(string at) { }
    protected override Task<bool> SignedIn(CancellationToken ct) =>
        Task.FromResult(HostIs(page?.Url, "www.dtr-service.it", "dtr-service.it") && !new Uri(page!.Url).AbsolutePath.StartsWith("/auth", StringComparison.OrdinalIgnoreCase));

    sealed record Page(List<List<string>> Rows, bool Next, int Total, int Page_, bool Login, bool Columns);
    async Task<Page> ReadPage(CancellationToken ct)
    {
        var json = JsonSerializer.Deserialize<string>(await Js(PortalJs.DtrList, ct)) ?? "{}";
        return JsonSerializer.Deserialize<Page>(json) ?? new Page([], false, 0, 0, false, false);
    }
    async Task<Page> WaitPage(int n, string previous, CancellationToken ct)
    {
        for (int i = 0; i < 75; i++)
        {
            var p = await ReadPage(ct);
            if (p.Login) Expired("Sessione DTR-Service scaduta: accedi di nuovo.");
            if (p.Rows.Count > 0 && p.Page_ == n && JsonSerializer.Serialize(p.Rows) != previous)
            { if (!p.Columns) throw new InvalidDataException("Le colonne dell'elenco DTR sono cambiate: dati precedenti conservati."); return p; }
            await Task.Delay(400, ct);
        }
        throw new TimeoutException("DTR-Service non ha caricato la pagina " + n + " dell'elenco.");
    }
    const int DetailBudget = 150;
    async Task<string?> ReadDetail(string serial, CancellationToken ct)
    {
        var arg = JsonSerializer.Serialize(serial);
        var opened = await Js("(()=>{const r=[...document.querySelectorAll('tbody tr')].find(r=>(r.querySelector('h6')?.innerText||'').trim()===" + arg + ");const i=r?.querySelector('.fa-info-circle');if(i){i.click();return true;}return false;})()", ct);
        if (opened != "true") return null;
        string? json = null;
        await Task.Delay(600, ct);
        await Js("(()=>{const b=[...document.querySelectorAll('[role=dialog] button,[role=dialog] [role=tab]')].find(x=>x.innerText.trim()==='LICENZE');b?.click();})()", ct);
        string? prev = null;
        for (int i = 0; i < 30 && json == null; i++)
        {
            await Task.Delay(i == 0 ? 1200 : 700, ct);
            var cur = JsonSerializer.Deserialize<string?>(await Js("(" + PortalJs.DtrDetail + ")(" + arg + ")", ct));
            if (cur != null && cur == prev) json = cur; prev = cur;
        }
        if (json == null) { await CloseDetail(ct); throw new TimeoutException("scheda non caricata"); }
        var ext = "";
        if (await Js("(()=>{const b=[...document.querySelectorAll('[role=dialog] button,[role=dialog] [role=tab]')].find(x=>x.innerText.trim()==='ESTENSIONI GE');if(b){b.click();return true;}return false;})()", ct) == "true")
        {
            await Task.Delay(900, ct);
            ext = JsonSerializer.Deserialize<string>(await Js("(()=>{const t=document.querySelector('[role=dialog]')?.innerText||'';const i=t.indexOf('ESTENSIONI GE'),j=t.indexOf('Ultimi errori');return i>=0&&j>i?t.slice(i+13,j).replace(/\\s+/g,' ').trim():'';})()", ct)) ?? "";
        }
        await CloseDetail(ct);
        var d = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(json)!; var m = d.ToDictionary(k => k.Key, v => (object)v.Value);
        m["Extensions"] = ext.StartsWith("Non sono state trovate", StringComparison.OrdinalIgnoreCase) ? "" : ext;
        return JsonSerializer.Serialize(m);
    }
    async Task CloseDetail(CancellationToken ct)
    {
        for (int i = 0; i < 20; i++)
        {
            var open = await Js("(()=>{const d=document.querySelector('[role=dialog]');if(!d)return false;[...d.querySelectorAll('button')].find(b=>b.innerText.trim()==='Chiudi')?.click();return true;})()", ct);
            if (open == "false") return; await Task.Delay(250, ct);
        }
    }
    protected override async Task<string> Read(CancellationToken ct)
    {
        await Go(ListUrl, ct);
        var details = new List<List<string>>(); var ages = LoadAges();
        var all = new List<List<string>>(); var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase); string last = ""; int total = 0;
        for (int n = 1; n <= 1000; n++)
        {
            var p = await WaitPage(n, last, ct); last = JsonSerializer.Serialize(p.Rows); if (p.Total > 0) total = p.Total;
            foreach (var r in p.Rows) if (r.Count == 9 && r[0].Length > 0 && seen.Add(r[0])) all.Add(r);
            SetStatus($"Lettura registratori: {all.Count}{(total > 0 ? " di " + total : "")}…");
            foreach (var r in p.Rows)
            {
                if (details.Count >= DetailBudget) break; var serial = r[0];
                if (ages.TryGetValue(serial, out var at) && DateTime.Now - at < TimeSpan.FromHours(20)) continue;
                try { var d = await ReadDetail(serial, ct); if (d != null) { details.Add([serial, d]); ages[serial] = DateTime.Now; } }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex) { Log("error", "DTR-Service, dettaglio " + serial + ": " + ex.Message); await CloseDetail(ct); }
                SetStatus($"Lettura registratori: {all.Count}{(total > 0 ? " di " + total : "")} · schede di dettaglio {details.Count}…");
            }
            if (!p.Next) break;
            var clicked = await Js("(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.querySelector('i.ki-bold-arrow-next'));if(b&&!b.disabled){b.click();return true;}return false;})()", ct);
            if (clicked != "true") break;
            if (n == 1000) throw new InvalidDataException("Troppe pagine: nessun dato parziale applicato.");
        }
        if (total > 0 && all.Count < total * 0.97) throw new InvalidDataException($"Letti {all.Count} registratori su {total}: lettura incompleta, dati precedenti conservati.");
        var msg = Apply("dtr", "Registratori DTR-Service", [.. Store.DtrHeaders], all);
        if (details.Count > 0) { msg += " · " + Apply("dtrdetail", "Dettagli DTR-Service", ["Matricola", "Dettaglio"], details); SaveAges(ages); }
        Heartbeat(true, "Aggiornato", total);
        return msg;
    }
}

// ───────────────────────── Ital Retail OnePlus: devices, device pages and orders
public sealed class ItalRunner(Store store, Hub hub) : PortalRunner(store, hub)
{
    const string Host = "oneplus.italretail.it";
    const string Dealer = "https://" + Host + "/it/dealer/";
    public override string Id => "ital";
    public override string Name => "Ital Retail";
    protected override string StartUrl => Dealer + "dashboard";
    protected override string? StatusKind => "italstatus";
    protected override string Product => "ITAL";
    protected override TimeSpan Every => TimeSpan.FromHours(2);
    public override string[] Kinds => ["ital", "italdetail", "italorders", "italstatus"];
    // the sign-in runs on the identity provider's own pages: any https page may be shown, data is read only on the portal host
    protected override bool Allowed(string url) => Https(url);
    protected override string LastSyncStored() => store.ItalState().LastSync;
    protected override void LastSyncSave(string at) { }
    protected override Task<bool> SignedIn(CancellationToken ct) =>
        Task.FromResult(HostIs(page?.Url, Host) && new Uri(page!.Url).AbsolutePath.Contains("/dealer", StringComparison.OrdinalIgnoreCase));

    sealed record Page(List<List<string>> Rows, int Total, bool Login, bool Columns);
    async Task<Page> ReadList(string url, string[] need, bool devices, CancellationToken ct)
    {
        await Go(url, ct); string? prev = null; var call = "(" + PortalJs.ItalList + ")(" + JsonSerializer.Serialize(need) + "," + (devices ? "true" : "false") + ")";
        for (int i = 0; i < 60; i++)
        {
            await Task.Delay(500, ct);
            var json = JsonSerializer.Deserialize<string>(await Js(call, ct)) ?? "{}";
            var p = JsonSerializer.Deserialize<Page>(json) ?? new Page([], -1, false, false);
            if (p.Login) Expired("Sessione Ital Retail scaduta: accedi di nuovo.");
            if (p.Total >= 0 && json == prev && (p.Rows.Count > 0 || p.Total == 0 || i > 12))
            { if (!p.Columns) throw new InvalidDataException("Le colonne di Ital Retail sono cambiate: dati precedenti conservati."); return p; }
            prev = json;
        }
        throw new TimeoutException("Ital Retail non ha caricato l'elenco.");
    }
    async Task<string?> ReadDetail(string id, string serial, CancellationToken ct)
    {
        await Go(Dealer + "devices/" + id, ct); string? prev = null; var call = "(" + PortalJs.ItalDetail + ")(" + JsonSerializer.Serialize(serial) + ")";
        for (int i = 0; i < 30; i++)
        {
            await Task.Delay(i == 0 ? 900 : 600, ct);
            var cur = JsonSerializer.Deserialize<string?>(await Js(call, ct));
            if (cur == "LOGIN") Expired("Sessione Ital Retail scaduta: accedi di nuovo.");
            if (cur != null && cur == prev) return cur; prev = cur;
        }
        return null;
    }
    const int PerPage = 100, DetailBudget = 80;
    protected override async Task<string> Read(CancellationToken ct)
    {
        var all = new List<List<string>>(); var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase); int total = 0;
        for (int n = 1; n <= 200; n++)
        {
            var p = await ReadList($"{Dealer}devices/?perPage={PerPage}&currentPage={n}&show_discontinued_devices=0", ["MATRICOLA", "TIPO DISPOSITIVO", "AGGIORNAMENTO", "ABBONAMENTO", "CLIENTE", "NOTE"], true, ct); total = p.Total;
            int added = 0; foreach (var r in p.Rows) if (r.Count == 7 && r[0].Length > 0 && seen.Add(r[0])) { all.Add(r); added++; }
            SetStatus($"Lettura dispositivi: {all.Count} di {total}…");
            if (all.Count >= total || added == 0) break;
        }
        if (all.Count < total) throw new InvalidDataException($"Letti {all.Count} dispositivi su {total}: lettura incompleta, dati precedenti conservati.");
        var ages = LoadAges(); var details = new List<List<string>>();
        foreach (var r in all.Where(r => r[1].Length > 0).OrderBy(r => ages.TryGetValue(r[0], out var a) ? a : DateTime.MinValue).Take(DetailBudget))
        {
            try { var d = await ReadDetail(r[1], r[0], ct); if (d != null) { details.Add([r[0], d]); ages[r[0]] = DateTime.Now; } else Log("error", "Ital Retail, scheda " + r[0] + ": non caricata."); }
            catch (OperationCanceledException) { throw; }
            catch (UnauthorizedAccessException) { throw; }
            catch (Exception ex) { Log("error", "Ital Retail, scheda " + r[0] + ": " + ex.Message); }
            SetStatus($"Lettura dispositivi: {all.Count} · schede di dettaglio {details.Count}…");
        }
        Page? orders = null;
        try { orders = await ReadList($"{Dealer}orders?perPage={PerPage}&currentPage=1", ["NUMERO", "DATA", "MATRICOLA", "CLIENTE", "TIPO DI ACQUISTO", "IMPORTO"], false, ct); }
        catch (OperationCanceledException) { throw; }
        catch (UnauthorizedAccessException) { throw; }
        catch (Exception ex) { Log("error", "Ital Retail, ordini: " + ex.Message); }
        var msg = Apply("ital", "Dispositivi Ital Retail", [.. Store.ItalHeaders], all);
        if (details.Count > 0) { msg += " · " + Apply("italdetail", "Dettagli Ital Retail", ["Matricola", "Dettaglio"], details); SaveAges(ages); }
        if (orders != null) msg += " · " + Apply("italorders", "Ordini Ital Retail", [.. Store.ItalOrderHeaders], orders.Rows.Where(r => r.Count == 6).ToList());
        Heartbeat(true, "Aggiornato", total);
        return msg;
    }
}

// ───────────────────────── RCH XDealer: device list through the portal's own JSON API (GET only)
public sealed class RchRunner(Store store, Hub hub) : PortalRunner(store, hub)
{
    const string Host = "xdealer.rch.it";
    const string Dealer = "https://" + Host + "/it/dealer/";
    public override string Id => "rch";
    public override string Name => "RCH XDealer";
    protected override string StartUrl => Dealer + "dashboard";
    protected override string? StatusKind => "rchstatus";
    protected override string Product => "RCH";
    protected override TimeSpan Every => TimeSpan.FromHours(2);
    public override string[] Kinds => ["rch", "rchstatus"];
    protected override bool Allowed(string url) => Https(url);
    protected override string LastSyncStored() => store.RchState().LastSync;
    protected override void LastSyncSave(string at) { }
    int misses;
    // the portal is a single-page app that passes through RCH's sign-in site by itself to renew the session:
    // signed in is confirmed with a real read-only call, signed out only when confirmed twice, 20 s apart
    protected override async Task<bool> SignedIn(CancellationToken ct)
    {
        if (!HostIs(page?.Url, Host)) return Miss();
        try { await Api("/dealer/api/v2/summary/counters", new { }, ct); misses = 0; return true; }
        catch (UnauthorizedAccessException) { return Miss(); }
    }
    bool Miss()
    {
        // first refusal: undecided (the next check, a moment later, confirms it)
        if (++misses == 1) throw new IOException("da confermare");
        return false;
    }
    async Task<JsonElement> Api(string path, object query, CancellationToken ct)
    {
        var raw = await Js("(" + PortalJs.RchApi + ")(" + JsonSerializer.Serialize(path) + "," + JsonSerializer.Serialize(query) + ").then(v=>JSON.stringify(v),e=>JSON.stringify({error:String(e)}))", ct);
        var s = JsonSerializer.Deserialize<string?>(raw) ?? throw new TimeoutException("RCH XDealer non ha risposto.");
        var r = JsonDocument.Parse(s).RootElement.Clone();
        if (r.TryGetProperty("login", out _)) Expired("Sessione RCH XDealer scaduta: accedi di nuovo.");
        if (r.TryGetProperty("error", out var er)) throw new InvalidDataException("RCH XDealer: " + er.GetString());
        return r.GetProperty("data");
    }
    const int PerPage = 100;
    protected override async Task<string> Read(CancellationToken ct)
    {
        if (!HostIs(page?.Url, Host)) await Go(StartUrl, ct);
        var all = new List<List<string>>(); var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase); int total = 0, read = 0;
        for (int n = 1; n <= 300; n++)
        {
            var data = await Api("/dealer/api/v2/devices", new Dictionary<string, object> { ["page"] = n, ["per_page"] = PerPage,
                ["include"] = "dealer-devices.customer,purchased_version,is_replaced_by,diagnostic_data", ["response_variant"] = "index-table" }, ct);
            if (!data.TryGetProperty("meta", out var meta) || !meta.TryGetProperty("total", out var tot)) throw new InvalidDataException("Risposta RCH XDealer cambiata: dati precedenti conservati.");
            total = tot.GetInt32();
            var mapped = JsonSerializer.Deserialize<List<List<string>>>(JsonSerializer.Deserialize<string>(await Js("JSON.stringify((" + PortalJs.RchMap + ")(" + data.GetRawText() + "))", ct)) ?? "[]") ?? [];
            var got = data.TryGetProperty("data", out var rows) ? rows.GetArrayLength() : 0; read += got;
            foreach (var r in mapped) if (r.Count == 2 && seen.Add(r[0])) all.Add(r);
            SetStatus($"Lettura dispositivi RCH: {all.Count} di {total}…");
            var last = meta.TryGetProperty("last_page", out var lp) ? lp.GetInt32() : 1;
            if (read >= total || n >= last || got == 0) break;
        }
        if (total == 0) { Heartbeat(true, "Nessun dispositivo", 0); return "Nessun dispositivo sul portale RCH."; }
        if (read < total) throw new InvalidDataException($"Letti {read} dispositivi RCH su {total}: lettura incompleta, dati precedenti conservati.");
        if (read > all.Count) Log("info", $"RCH XDealer: {read - all.Count} dispositivi senza matricola o doppi non importati.");
        var msg = Apply("rch", "Dispositivi RCH XDealer", [.. Store.RchHeaders], all);
        Heartbeat(true, "Aggiornato", total);
        return msg;
    }
}
