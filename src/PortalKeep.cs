using Microsoft.Web.WebView2.Core;
using System.Windows.Threading;

namespace TopCash.Licenze;
/// Keeps the portals signed in across Studio restarts. Many portals give "session" cookies, which the browser
/// throws away when the window closes: here they are kept on disk (in the portal's own private profile, on this PC
/// only) with a 30-day expiry, refreshed after every page and every few minutes. No password is ever read or stored:
/// only the cookies the portal itself issued, which it can still expire on its side.
static class PortalKeep
{
    static readonly List<CoreWebView2> cores = [];
    static readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMinutes(5) };
    static PortalKeep() { timer.Tick += async (_, _) => await SaveAll(); }

    public static void Attach(CoreWebView2 core)
    {
        lock (cores) cores.Add(core);
        var pending = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        pending.Tick += async (_, _) => { pending.Stop(); await Save(core); };
        core.NavigationCompleted += (_, _) => { pending.Stop(); pending.Start(); };// once per page, after it settles
        if (!timer.IsEnabled) timer.Start();
    }
    public static void Detach(CoreWebView2? core) { if (core != null) lock (cores) cores.Remove(core); }

    /// Every portal at once: used every few minutes and when Studio closes (the last word before the browsers go).
    public static async Task SaveAll()
    {
        CoreWebView2[] all; lock (cores) all = [.. cores];
        await Task.WhenAll(all.Select(Save));
    }

    static async Task Save(CoreWebView2 core)
    {
        try
        {
            var until = DateTime.Now.AddDays(30);
            foreach (var c in await core.CookieManager.GetCookiesAsync(""))
                if (c.IsSession) { c.Expires = until; core.CookieManager.AddOrUpdateCookie(c); }
        }
        catch { }// a browser already closed: nothing to keep
    }
}
