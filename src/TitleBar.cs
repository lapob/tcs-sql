using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows;
using System.Windows.Media;

namespace TopCash.Licenze;
/// The windows' title bar in the colours of the app: the same background as the page, light or dark, with matching
/// text and border, so window and content read as one surface. It stays the real Windows title bar (snap layouts,
/// double click, drag, the usual buttons). On Windows 10 only the dark/light switch applies.
static class TitleBar
{
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    const int DarkMode = 20, DarkModeOld = 19, BorderColor = 34, CaptionColor = 35, TextColor = 36;

    // the last colours the page asked for: windows opened later (the portals) take them too
    static readonly string saved = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TopCash Studio", "barra.txt");
    static bool dark = SystemDark();
    static Color? back;
    static TitleBar()
    {   // the colours of the last session, so the bar is right from the first frame (before the page has loaded)
        try { var p = File.ReadAllText(saved).Split(','); dark = p[0] == "1"; back = Color.FromRgb(byte.Parse(p[1]), byte.Parse(p[2]), byte.Parse(p[3])); } catch { }
    }
    static readonly List<WeakReference<Window>> windows = [];

    public static void Attach(Window w)
    {
        lock (windows) windows.Add(new(w));
        if (new WindowInteropHelper(w).Handle != IntPtr.Zero) Paint(w);
        else w.SourceInitialized += (_, _) => Paint(w);
    }

    public static void Set(bool isDark, byte r, byte g, byte b)
    {
        if (dark == isDark && back == Color.FromRgb(r, g, b)) return;
        dark = isDark; back = Color.FromRgb(r, g, b);
        try { Directory.CreateDirectory(Path.GetDirectoryName(saved)!); File.WriteAllText(saved, $"{(isDark ? 1 : 0)},{r},{g},{b}"); } catch { }
        Window[] all;
        lock (windows) { windows.RemoveAll(x => !x.TryGetTarget(out _)); all = [.. windows.Select(x => x.TryGetTarget(out var w) ? w : null).OfType<Window>()]; }
        foreach (var w in all) Paint(w);
    }

    static void Paint(Window w)
    {
        try
        {
            var h = new WindowInteropHelper(w).Handle; if (h == IntPtr.Zero) return;
            int d = dark ? 1 : 0;
            if (DwmSetWindowAttribute(h, DarkMode, ref d, 4) != 0) DwmSetWindowAttribute(h, DarkModeOld, ref d, 4);
            var c = back ?? (dark ? Color.FromRgb(13, 15, 20) : Color.FromRgb(245, 247, 251));
            int bg = c.R | c.G << 8 | c.B << 16;
            int fg = dark ? 0xEEEAE6 : 0x2A2420;// soft white / soft ink (COLORREF is BGR)
            DwmSetWindowAttribute(h, CaptionColor, ref bg, 4);
            DwmSetWindowAttribute(h, BorderColor, ref bg, 4);
            DwmSetWindowAttribute(h, TextColor, ref fg, 4);
            w.Background = new SolidColorBrush(c);// no white flash while resizing or before the page draws
        }
        catch { }
    }

    static bool SystemDark()
    {
        try { return Microsoft.Win32.Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is int v && v == 0; }
        catch { return false; }
    }
}
