using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace TopCash.Server;

/// A headless Chrome (or Edge) owned by the server, one per portal, each with its own private profile under
/// .runtime\portali\<id>. Driven with the Chrome DevTools Protocol over a local pipe-like websocket (127.0.0.1 only,
/// random port): nothing is reachable from the network. The browser only shows what the portal sends; the server reads
/// list pages exactly like the Windows app did, and the owner signs in through a live picture of the page.
public sealed class HeadlessBrowser : IAsyncDisposable
{
    readonly string profile;
    Process? proc;
    ClientWebSocket? ws;
    CancellationTokenSource? cts;
    int nextId;
    readonly ConcurrentDictionary<int, TaskCompletionSource<JsonElement>> pending = new();
    /// (method, params, sessionId) for every protocol event.
    public event Action<string, JsonElement, string?>? Event;
    public bool Alive => proc is { HasExited: false } && ws?.State == WebSocketState.Open;
    public string Executable { get; private set; } = "";

    public HeadlessBrowser(string profile) { this.profile = profile; }

    /// Chrome first (installed for all users), then Edge. TOPCASH_CHROME overrides both.
    public static string? FindExecutable()
    {
        var env = Environment.GetEnvironmentVariable("TOPCASH_CHROME");
        string[] list = [env ?? "",
            @"C:\Program Files\Google\Chrome\Application\chrome.exe", @"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
            @"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", @"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge"];
        return list.FirstOrDefault(p => p.Length > 0 && File.Exists(p));
    }

    public async Task Start(CancellationToken ct, bool noSandbox = false)
    {
        var exe = FindExecutable() ?? throw new InvalidOperationException("Sul PC del server non c'è Google Chrome né Microsoft Edge: installane uno per collegare i portali.");
        Executable = exe;
        Directory.CreateDirectory(profile);
        KillOrphan();
        var portFile = Path.Combine(profile, "DevToolsActivePort");
        try { File.Delete(portFile); } catch { }
        var args = new List<string> {
            "--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1", $"--user-data-dir={profile}",
            "--no-first-run", "--no-default-browser-check", "--disable-extensions", "--disable-sync", "--disable-background-networking",
            "--disable-component-update", "--disable-default-apps", "--mute-audio", "--hide-scrollbars", "--lang=it-IT",
            "--window-size=1280,860", "--disable-features=Translate,OptimizationHints,MediaRouter", "--password-store=basic", "about:blank" };
        if (noSandbox) args.Insert(0, "--no-sandbox");
        var psi = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        proc = Process.Start(psi) ?? throw new InvalidOperationException("Il browser del server non è partito.");
        proc.ErrorDataReceived += (_, _) => { }; proc.OutputDataReceived += (_, _) => { };
        proc.BeginErrorReadLine(); proc.BeginOutputReadLine();
        try { File.WriteAllText(Path.Combine(profile, "browser.pid"), proc.Id.ToString()); } catch { }
        string? endpoint = null;
        for (int i = 0; i < 100 && endpoint == null; i++)
        {
            await Task.Delay(200, ct);
            if (proc.HasExited) throw new InvalidOperationException($"Il browser del server si è chiuso subito (codice {proc.ExitCode}).");
            try
            {
                var lines = File.ReadAllLines(portFile);
                if (lines.Length >= 2 && int.TryParse(lines[0], out var port)) endpoint = $"ws://127.0.0.1:{port}{lines[1].Trim()}";
            }
            catch (IOException) { }
        }
        if (endpoint == null) throw new TimeoutException("Il browser del server non risponde.");
        ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
        await ws.ConnectAsync(new Uri(endpoint), ct);
        cts = new CancellationTokenSource();
        _ = Task.Run(() => ReadLoop(cts.Token));
    }

    /// A browser left behind by a crash keeps the profile locked: stop it before starting a new one.
    void KillOrphan()
    {
        try
        {
            var f = Path.Combine(profile, "browser.pid");
            if (!File.Exists(f) || !int.TryParse(File.ReadAllText(f), out var pid)) return;
            using var p = Process.GetProcessById(pid);
            var name = p.ProcessName.ToLowerInvariant();
            if (name.Contains("chrome") || name.Contains("msedge") || name.Contains("chromium")) p.Kill(true);
        }
        catch { }
    }

    async Task ReadLoop(CancellationToken ct)
    {
        var buf = new byte[1 << 16];
        using var ms = new MemoryStream();
        try
        {
            while (!ct.IsCancellationRequested && ws!.State == WebSocketState.Open)
            {
                var r = await ws.ReceiveAsync(buf, ct);
                if (r.MessageType == WebSocketMessageType.Close) break;
                ms.Write(buf, 0, r.Count);
                if (!r.EndOfMessage) continue;
                JsonElement root;
                try { using var doc = JsonDocument.Parse(ms.ToArray()); root = doc.RootElement.Clone(); }
                finally { ms.SetLength(0); }
                if (root.TryGetProperty("id", out var idEl))
                {
                    if (pending.TryRemove(idEl.GetInt32(), out var tcs))
                    {
                        if (root.TryGetProperty("error", out var err)) tcs.TrySetException(new CdpException(err.TryGetProperty("message", out var m) ? m.GetString() ?? "errore" : "errore"));
                        else tcs.TrySetResult(root.TryGetProperty("result", out var res) ? res : default);
                    }
                }
                else if (root.TryGetProperty("method", out var method))
                {
                    var sid = root.TryGetProperty("sessionId", out var s) ? s.GetString() : null;
                    var prm = root.TryGetProperty("params", out var p) ? p : default;
                    try { Event?.Invoke(method.GetString() ?? "", prm, sid); } catch { }
                }
            }
        }
        catch { }
        foreach (var t in pending.Values) t.TrySetException(new CdpException("Il browser del server si è chiuso."));
        pending.Clear();
    }

    readonly SemaphoreSlim sendLock = new(1, 1);
    public async Task<JsonElement> Send(string method, object? prm = null, string? session = null, int timeoutMs = 30000, CancellationToken ct = default)
    {
        if (ws?.State != WebSocketState.Open) throw new CdpException("Il browser del server non è attivo.");
        var id = Interlocked.Increment(ref nextId);
        var tcs = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        pending[id] = tcs;
        var msg = new Dictionary<string, object?> { ["id"] = id, ["method"] = method, ["params"] = prm ?? new { } };
        if (session != null) msg["sessionId"] = session;
        var bytes = JsonSerializer.SerializeToUtf8Bytes(msg);
        await sendLock.WaitAsync(ct);
        try { await ws.SendAsync(bytes, WebSocketMessageType.Text, true, ct); }
        finally { sendLock.Release(); }
        try { return await tcs.Task.WaitAsync(TimeSpan.FromMilliseconds(timeoutMs), ct); }
        catch (TimeoutException) { pending.TryRemove(id, out _); throw new TimeoutException("Il browser del server non ha risposto (" + method + ")."); }
    }

    public async ValueTask DisposeAsync()
    {
        try { if (ws?.State == WebSocketState.Open) await Send("Browser.close", null, null, 3000); } catch { }
        try { cts?.Cancel(); } catch { }
        try { ws?.Dispose(); } catch { }
        try { if (proc is { HasExited: false }) { await Task.WhenAny(proc.WaitForExitAsync(), Task.Delay(3000)); if (!proc.HasExited) proc.Kill(true); } } catch { }
        try { proc?.Dispose(); } catch { }
        try { File.Delete(Path.Combine(profile, "browser.pid")); } catch { }
    }
}

public sealed class CdpException(string message) : Exception(message);

/// The single tab of a portal browser: navigation limited to the portal's own sites, reading by script, picture and input
/// for the owner's sign-in. Mirrors the WebView2 calls used by the Windows app (ExecuteScriptAsync returns JSON text).
public sealed class PortalPage
{
    readonly HeadlessBrowser b;
    readonly Func<string, bool> allowed;
    public string Session { get; private set; } = "";
    string targetId = "";
    public string Url { get; private set; } = "about:blank";
    /// Raised (on a pool thread) when the page address changes, also inside a single-page app.
    public Action? UrlChanged { get; set; }
    TaskCompletionSource<bool>? load;
    public const int Width = 1280, Height = 860;
    /// Size the page is drawn at now: the desktop size, or a phone size while the owner signs in from a phone.
    public int ViewWidth { get; private set; } = Width;
    public int ViewHeight { get; private set; } = Height;
    public async Task SetViewport(int w, int h, CancellationToken ct = default)
    {
        w = Math.Clamp(w, 320, Width); h = Math.Clamp(h, 480, 1000);
        if (w == ViewWidth && h == ViewHeight) return;
        var mobile = w < 760;
        await Cmd("Emulation.setDeviceMetricsOverride", new { width = w, height = h, deviceScaleFactor = mobile ? 2 : 1, mobile }, ct);
        await Cmd("Emulation.setTouchEmulationEnabled", new { enabled = mobile, maxTouchPoints = mobile ? 5 : 0 }, ct);
        ViewWidth = w; ViewHeight = h;
    }

    PortalPage(HeadlessBrowser b, Func<string, bool> allowed) { this.b = b; this.allowed = allowed; }

    public static async Task<PortalPage> Open(HeadlessBrowser b, Func<string, bool> allowed, CancellationToken ct)
    {
        var p = new PortalPage(b, allowed);
        b.Event += p.OnEvent;
        await b.Send("Browser.setDownloadBehavior", new { behavior = "deny" }, ct: ct);
        await b.Send("Target.setDiscoverTargets", new { discover = true }, ct: ct);
        var targets = await b.Send("Target.getTargets", ct: ct);
        var page = targets.GetProperty("targetInfos").EnumerateArray().FirstOrDefault(t => t.GetProperty("type").GetString() == "page");
        p.targetId = page.ValueKind == JsonValueKind.Object ? page.GetProperty("targetId").GetString()! :
            (await b.Send("Target.createTarget", new { url = "about:blank" }, ct: ct)).GetProperty("targetId").GetString()!;
        p.Session = (await b.Send("Target.attachToTarget", new { targetId = p.targetId, flatten = true }, ct: ct)).GetProperty("sessionId").GetString()!;
        await p.Cmd("Page.enable", null, ct);
        await p.Cmd("Emulation.setDeviceMetricsOverride", new { width = Width, height = Height, deviceScaleFactor = 1, mobile = false }, ct);
        // every page load (not images or scripts) passes the portal's address rules first
        await p.Cmd("Fetch.enable", new { patterns = new[] { new { urlPattern = "*", resourceType = "Document", requestStage = "Request" } } }, ct);
        // a link that would open a new window opens in the same tab instead
        await p.Cmd("Page.addScriptToEvaluateOnNewDocument", new { source = "window.open=function(u){if(u)location.href=u;return window;};" }, ct);
        return p;
    }

    public Task<JsonElement> Cmd(string method, object? prm, CancellationToken ct = default, int timeoutMs = 30000) => b.Send(method, prm, Session, timeoutMs, ct);

    void OnEvent(string method, JsonElement p, string? sid)
    {
        switch (method)
        {
            case "Fetch.requestPaused" when sid == Session:
                var url = p.GetProperty("request").GetProperty("url").GetString() ?? "";
                var rid = p.GetProperty("requestId").GetString();
                _ = allowed(url) ? Cmd("Fetch.continueRequest", new { requestId = rid }) : Cmd("Fetch.failRequest", new { requestId = rid, errorReason = "BlockedByClient" });
                break;
            case "Page.frameNavigated" when sid == Session:
                var f = p.GetProperty("frame");
                if (!f.TryGetProperty("parentId", out _)) { Url = f.GetProperty("url").GetString() ?? Url; UrlChanged?.Invoke(); }
                break;
            case "Page.navigatedWithinDocument" when sid == Session:
                Url = p.GetProperty("url").GetString() ?? Url; UrlChanged?.Invoke();
                break;
            case "Page.loadEventFired" when sid == Session:
                load?.TrySetResult(true);
                break;
            case "Page.javascriptDialogOpening" when sid == Session:
                // alerts are acknowledged; confirmations are declined (the server never agrees to anything on a portal)
                var type = p.GetProperty("type").GetString();
                _ = Cmd("Page.handleJavaScriptDialog", new { accept = type is "alert" or "beforeunload" });
                break;
            case "Target.targetCreated" or "Target.targetInfoChanged":
                var info = p.GetProperty("targetInfo");
                if (info.GetProperty("type").GetString() == "page" && info.TryGetProperty("openerId", out var op) && op.GetString() == targetId)
                {
                    var u = info.GetProperty("url").GetString() ?? "";
                    if (u.Length > 0 && u != "about:blank")
                    {
                        _ = b.Send("Target.closeTarget", new { targetId = info.GetProperty("targetId").GetString() });
                        if (allowed(u)) _ = Navigate(u, CancellationToken.None);
                    }
                }
                break;
        }
    }

    /// Same contract as WebView2's ExecuteScriptAsync: the result as JSON text ("null" for nothing or an error).
    public async Task<string> Eval(string js, CancellationToken ct = default)
    {
        var r = await Cmd("Runtime.evaluate", new { expression = js, awaitPromise = true, returnByValue = true }, ct, 60000);
        if (r.TryGetProperty("exceptionDetails", out _)) return "null";
        var v = r.GetProperty("result");
        return v.TryGetProperty("value", out var val) ? val.GetRawText() : "null";
    }

    /// Opens an address and waits for the page to load (false when the portal did not answer or the address is refused).
    public async Task<bool> Navigate(string url, CancellationToken ct, int timeoutSec = 40)
    {
        if (!allowed(url)) return false;
        load = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var r = await Cmd("Page.navigate", new { url }, ct);
        if (r.TryGetProperty("errorText", out var e) && !string.IsNullOrEmpty(e.GetString())) return false;
        if (!r.TryGetProperty("loaderId", out _)) return true; // same page (single-page app): nothing more to wait for
        try { await load.Task.WaitAsync(TimeSpan.FromSeconds(timeoutSec), ct); return true; }
        catch (TimeoutException) { return false; }
    }

    public async Task<byte[]> Picture(CancellationToken ct = default)
    {
        var r = await Cmd("Page.captureScreenshot", new { format = "jpeg", quality = ViewWidth < 760 ? 70 : 62, captureBeyondViewport = false }, ct);
        return Convert.FromBase64String(r.GetProperty("data").GetString() ?? "");
    }

    public async Task Click(double x, double y, CancellationToken ct = default)
    {
        await Cmd("Input.dispatchMouseEvent", new { type = "mouseMoved", x, y }, ct);
        await Cmd("Input.dispatchMouseEvent", new { type = "mousePressed", x, y, button = "left", clickCount = 1 }, ct);
        await Cmd("Input.dispatchMouseEvent", new { type = "mouseReleased", x, y, button = "left", clickCount = 1 }, ct);
    }
    public Task Scroll(double x, double y, double dy, CancellationToken ct = default) =>
        Cmd("Input.dispatchMouseEvent", new { type = "mouseWheel", x, y, deltaX = 0, deltaY = dy }, ct);
    public Task Type(string text, CancellationToken ct = default) => Cmd("Input.insertText", new { text }, ct);

    static readonly Dictionary<string, (string Code, int Vk, string Text)> keys = new()
    {
        ["Enter"] = ("Enter", 13, "\r"), ["Tab"] = ("Tab", 9, ""), ["Backspace"] = ("Backspace", 8, ""), ["Escape"] = ("Escape", 27, ""),
        ["Delete"] = ("Delete", 46, ""), ["ArrowLeft"] = ("ArrowLeft", 37, ""), ["ArrowRight"] = ("ArrowRight", 39, ""),
        ["ArrowUp"] = ("ArrowUp", 38, ""), ["ArrowDown"] = ("ArrowDown", 40, ""), ["Space"] = ("Space", 32, " "),
    };
    public async Task Key(string key, CancellationToken ct = default)
    {
        if (!keys.TryGetValue(key, out var k)) return;
        var name = key == "Space" ? " " : key;
        var down = new Dictionary<string, object> { ["type"] = k.Text.Length > 0 ? "keyDown" : "rawKeyDown", ["key"] = name, ["code"] = k.Code, ["windowsVirtualKeyCode"] = k.Vk, ["nativeVirtualKeyCode"] = k.Vk };
        if (k.Text.Length > 0) down["text"] = k.Text;
        await Cmd("Input.dispatchKeyEvent", down, ct);
        await Cmd("Input.dispatchKeyEvent", new { type = "keyUp", key = name, code = k.Code, windowsVirtualKeyCode = k.Vk, nativeVirtualKeyCode = k.Vk }, ct);
    }
    public async Task Back(CancellationToken ct = default) => await Eval("history.back()", ct);

    /// Session cookies would be lost when the browser restarts (server update, reboot): they are kept for 30 days,
    /// exactly like the Windows app did. Only the portal's own cookies, stored in this portal's private profile.
    public async Task KeepSession(CancellationToken ct = default)
    {
        var r = await Cmd("Network.getAllCookies", null, ct);
        var until = DateTimeOffset.UtcNow.AddDays(30).ToUnixTimeSeconds();
        var keep = new List<Dictionary<string, object?>>();
        foreach (var c in r.GetProperty("cookies").EnumerateArray())
        {
            if (!c.TryGetProperty("session", out var s) || !s.GetBoolean()) continue;
            var d = new Dictionary<string, object?>
            {
                ["name"] = c.GetProperty("name").GetString(), ["value"] = c.GetProperty("value").GetString(),
                ["domain"] = c.GetProperty("domain").GetString(), ["path"] = c.GetProperty("path").GetString(),
                ["secure"] = c.GetProperty("secure").GetBoolean(), ["httpOnly"] = c.GetProperty("httpOnly").GetBoolean(), ["expires"] = until,
            };
            if (c.TryGetProperty("sameSite", out var ss)) d["sameSite"] = ss.GetString();
            keep.Add(d);
        }
        if (keep.Count > 0) await Cmd("Network.setCookies", new { cookies = keep }, ct);
    }
}
