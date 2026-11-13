using System.Globalization;
using System.Text;

namespace TopCash.Licenze;

/// One account (or one line) of the statements: this period and the comparison period.
public sealed record BilLine(string Code, string Label, decimal Amount, decimal Prev);
/// A group of accounts (parent account of Mastro's chart) in a section of the statements.
public sealed record BilGroup(string Code, string Label, string Section, decimal Amount, decimal Prev, List<BilLine> Accounts);
/// One month: revenue, costs, result, and liquidity (cash + banks) at the month end.
public sealed record BilMonth(string Month, decimal Revenue, decimal Costs, decimal Result, decimal Liquidity);
/// Balance sheet and income statement from Mastro's general ledger (read only), computed once on the server and shown
/// the same on every app. Month = balance date (end of that month); PrevEnd = 31 December of the previous year.
/// Income figures are year to date (and the same months of last year): year-end entries (depreciation, accruals,
/// stock) are booked by the accountant at closing, so the result in progress is an estimate.
public sealed record Bilancio(string At, string Month, string PrevEnd, bool HasIncome, bool HasBalance, string Note,
    decimal Revenue, decimal Costs, decimal Result, decimal RevenuePrev, decimal CostsPrev, decimal ResultPrev, decimal RevenueLastYear, decimal ResultLastYear,
    List<BilGroup> Income, List<BilGroup> Assets, List<BilGroup> Liabilities,
    decimal TotalAssets, decimal TotalLiabilities, decimal TotalAssetsPrev, decimal TotalLiabilitiesPrev,
    decimal Liquidity, decimal LiquidityPrev, decimal FinancialDebt, decimal NetFinancial, decimal NetFinancialPrev,
    decimal Receivables, decimal ReceivablesPrev, decimal Payables, decimal PayablesPrev, decimal Vat, decimal Equity, decimal EquityPrev,
    int Dso, int Dpo, decimal Revenue12, decimal Purchases12, List<BilMonth> Months, List<BilMonth> MonthsPrev);

public sealed partial class Store
{
    public List<LedgerRow> Patrimony() => MastroExtra<LedgerRow>("patrimony");

    /// Section of the balance sheet for a group of class-1 accounts, from its name (Mastro's chart has no section code).
    static string BilSection(string label, decimal amount)
    {
        var l = label.ToUpperInvariant();
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"FOND[OI] SVALUT")) return "Crediti";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"CESPIT|AMMORT|MIGLIORI|IMMOBILIZZ|IMMATERIAL|PARTECIPAZ")) return "Immobilizzazioni";
        if (l.Contains("RIMANENZ")) return "Rimanenze";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"FINANZ|FINAZ|MUTU|LEASING")) return "Debiti finanziari";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"^CASSA|^BANCH")) return "Liquidità";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"CAPITALE|RISERV|RISULTATO|UTILE|PERDIT")) return "Patrimonio netto";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"T\.?F\.?R")) return "Fondi e TFR";
        // receivables and payables stay on their side even when a balance turns the other way (it shows as a negative)
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"^DEBIT|FORNITOR|RETTIFICHE PASSIV")) return "Debiti";
        if (System.Text.RegularExpressions.Regex.IsMatch(l, @"^CREDIT|CLIENTI|PORTAFOGLIO|RETTIFICHE ATTIV")) return "Crediti";
        return amount >= 0 ? "Crediti" : "Debiti";
    }
    static readonly string[] AssetSections = ["Immobilizzazioni", "Rimanenze", "Crediti", "Liquidità"];
    static readonly string[] LiabilitySections = ["Patrimonio netto", "Fondi e TFR", "Debiti finanziari", "Debiti"];

    public Bilancio Bilancio(DateTime? now = null)
    {
        var today = (now ?? DateTime.Today).Date; var y = today.Year; var curM = today.ToString("yyyy-MM");
        var ledger = Ledger(); var pat = Patrimony();
        string M(int year, int month) => $"{year:D4}-{month:D2}";
        // ── income statement: year to date and the same months of last year
        var prevM = M(y - 1, today.Month);
        bool Cur(string m) => string.CompareOrdinal(m, M(y, 1)) >= 0 && string.CompareOrdinal(m, curM) <= 0;
        bool PrevYtd(string m) => string.CompareOrdinal(m, M(y - 1, 1)) >= 0 && string.CompareOrdinal(m, prevM) <= 0;
        bool PrevAll(string m) => m.StartsWith($"{y - 1:D4}-", StringComparison.Ordinal);
        var pl = ledger.Where(l => l.Kind is "R" or "C").ToList();
        var income = pl.GroupBy(l => (l.Kind, G: l.Group.Length > 0 ? l.Group : l.Label)).Select(g =>
        {
            var accounts = g.GroupBy(l => l.Account).Select(a => new BilLine(a.Key, a.First().Label, Math.Round(a.Where(l => Cur(l.Month)).Sum(l => l.Amount), 2), Math.Round(a.Where(l => PrevYtd(l.Month)).Sum(l => l.Amount), 2)))
                .Where(a => a.Amount != 0 || a.Prev != 0).OrderByDescending(a => Math.Abs(a.Amount)).ThenByDescending(a => Math.Abs(a.Prev)).ToList();
            return new BilGroup(g.Key.G, g.Key.G, g.Key.Kind == "R" ? "Ricavi" : "Costi", accounts.Sum(a => a.Amount), accounts.Sum(a => a.Prev), accounts);
        }).Where(g => g.Accounts.Count > 0).OrderBy(g => g.Section == "Ricavi" ? 0 : 1).ThenByDescending(g => Math.Abs(g.Amount)).ToList();
        decimal Sum(string kind, Func<string, bool> when) => Math.Round(pl.Where(l => l.Kind == kind && when(l.Month)).Sum(l => l.Amount), 2);
        var rev = Sum("R", Cur); var cost = Sum("C", Cur); var revP = Sum("R", PrevYtd); var costP = Sum("C", PrevYtd);
        var revLY = Sum("R", PrevAll); var costLY = Sum("C", PrevAll);

        // ── balance sheet: opening of the year + movements up to the month end (closing entries are never read)
        var note = "";
        var opened = pat.Where(p => p.Kind == "A").Select(p => p.Month[..4]).ToHashSet();
        var labels = pat.GroupBy(p => p.Account).ToDictionary(g => g.Key, g => (g.First().Label, g.First().Group));
        Dictionary<string, decimal> Bal(int year, string until, int depth = 0)
        {
            var b = new Dictionary<string, decimal>();
            void Add(string acc, decimal v) => b[acc] = b.GetValueOrDefault(acc) + v;
            var ys = $"{year:D4}";
            if (opened.Contains(ys))
                foreach (var p in pat.Where(p => p.Kind == "A" && p.Month.StartsWith(ys, StringComparison.Ordinal))) Add(p.Account, p.Amount);
            else if (depth == 0 && pat.Any(p => p.Month.StartsWith($"{year - 1:D4}", StringComparison.Ordinal)))
            {
                // opening not booked yet in Mastro (it comes with the closing of last year): carry last year's balances forward
                foreach (var (acc, v) in Bal(year - 1, M(year - 1, 12), 1)) Add(acc, v);
                var lastResult = -(pl.Where(l => l.Month.StartsWith($"{year - 1:D4}-", StringComparison.Ordinal)).Sum(l => l.Kind == "R" ? l.Amount : -l.Amount));
                if (lastResult != 0) { Add("~res", lastResult); labels.TryAdd("~res", ($"Risultato {year - 1} da chiudere", "1.69|RISULTATO ESERCIZIO")); }
            }
            foreach (var p in pat.Where(p => p.Kind == "P" && p.Month.StartsWith(ys, StringComparison.Ordinal) && string.CompareOrdinal(p.Month, until) <= 0)) Add(p.Account, p.Amount);
            return b;
        }
        var hasBal = pat.Count > 0;
        if (hasBal && !opened.Contains($"{y:D4}")) note = $"L’apertura dei conti del {y} non è ancora registrata in Mastro: i saldi partono dalla chiusura del {y - 1}.";
        var now_ = hasBal ? Bal(y, curM) : []; var prev = hasBal ? Bal(y - 1, M(y - 1, 12)) : [];
        string GroupOf(string acc) => labels.TryGetValue(acc, out var l) ? l.Group : acc;
        string Label(string acc) => labels.TryGetValue(acc, out var l) ? l.Label : acc;
        // RiBa presented to the bank "salvo buon fine" ("Effetti … all'incasso"): a short-term advance from the bank, not cash
        string Place(string acc) => System.Text.RegularExpressions.Regex.IsMatch(Label(acc).ToUpperInvariant(), @"EFFETTI.*INCASSO|ANTICIP|S\.B\.F|SALVO BUON FINE")
            && GroupOf(acc).ToUpperInvariant().Contains("BANC") ? "~anticipi|Anticipi bancari su effetti (salvo buon fine)" : GroupOf(acc);
        var groups = now_.Keys.Concat(prev.Keys).Distinct().GroupBy(Place).Select(g =>
        {
            var parts = g.Key.Split('|', 2); var code = parts[0]; var label = parts.Length > 1 ? Pretty(parts[1]) : code;
            var amount = g.Sum(a => now_.GetValueOrDefault(a)); var before = g.Sum(a => prev.GetValueOrDefault(a));
            var section = code == "~anticipi" ? "Debiti finanziari" : BilSection(label, amount != 0 ? amount : before);
            var liab = LiabilitySections.Contains(section); var sign = liab ? -1 : 1;
            var accounts = g.Select(a => new BilLine(a, Label(a), Math.Round(sign * now_.GetValueOrDefault(a), 2), Math.Round(sign * prev.GetValueOrDefault(a), 2)))
                .Where(a => a.Amount != 0 || a.Prev != 0).OrderByDescending(a => Math.Abs(a.Amount)).ToList();
            return new BilGroup(code, label, section, Math.Round(sign * amount, 2), Math.Round(sign * before, 2), accounts);
        }).Where(g => g.Accounts.Count > 0).ToList();
        // result in progress: what makes assets equal liabilities (the same as revenue minus costs of the ledger, if all is booked)
        var resNow = Math.Round(now_.Values.Sum(), 2); var resPrev = Math.Round(prev.Values.Sum(), 2);
        if (hasBal) groups.Add(new BilGroup("~utile", resNow >= 0 ? "Utile dell’esercizio in corso" : "Perdita dell’esercizio in corso", "Patrimonio netto", resNow, resPrev,
            [new BilLine("~utile", "Ricavi meno costi registrati finora", resNow, resPrev)]));
        int Order(BilGroup g, string[] secs) => Array.IndexOf(secs, g.Section);
        var assets = groups.Where(g => AssetSections.Contains(g.Section)).OrderBy(g => Order(g, AssetSections)).ThenByDescending(g => Math.Abs(g.Amount)).ToList();
        var liabilities = groups.Where(g => LiabilitySections.Contains(g.Section)).OrderBy(g => Order(g, LiabilitySections)).ThenBy(g => g.Code == "~utile" ? 1 : 0).ThenByDescending(g => Math.Abs(g.Amount)).ToList();
        decimal Sec(IEnumerable<BilGroup> l, Func<BilGroup, bool> f, bool prevYear = false) => Math.Round(l.Where(f).Sum(g => prevYear ? g.Prev : g.Amount), 2);
        bool Like(BilGroup g, string re) => System.Text.RegularExpressions.Regex.IsMatch(g.Label.ToUpperInvariant(), re);
        var liq = Sec(groups, g => g.Section == "Liquidità"); var liqP = Sec(groups, g => g.Section == "Liquidità", true);
        var fin = Sec(groups, g => g.Section == "Debiti finanziari"); var finP = Sec(groups, g => g.Section == "Debiti finanziari", true);
        var recv = Sec(groups, g => Like(g, "CLIENTI") && !Like(g, "FOND")); var recvP = Sec(groups, g => Like(g, "CLIENTI") && !Like(g, "FOND"), true);
        var pay = Sec(groups, g => Like(g, "FORNITORI")); var payP = Sec(groups, g => Like(g, "FORNITORI"), true);
        // VAT: positive = credit towards the State, negative = VAT to pay
        var vat = Math.Round(groups.Where(g => Like(g, @"\bIVA\b")).Sum(g => LiabilitySections.Contains(g.Section) ? -g.Amount : g.Amount), 2);
        var equity = Sec(groups, g => g.Section == "Patrimonio netto"); var equityP = Sec(groups, g => g.Section == "Patrimonio netto", true);
        // average days to collect / to pay (estimate: balances include VAT, the ledger does not)
        var from12 = today.AddMonths(-11); bool In12(string m) => string.CompareOrdinal(m, M(from12.Year, from12.Month)) >= 0 && string.CompareOrdinal(m, curM) <= 0;
        var rev12 = Math.Round(pl.Where(l => l.Kind == "R" && In12(l.Month)).Sum(l => l.Amount), 2);
        var buy12 = Math.Round(ledger.Where(l => l.Kind == "F" && In12(l.Month)).Sum(l => l.Amount), 2);
        int Days(decimal bal, decimal year) => year > 0 && bal > 0 ? (int)Math.Min(999, Math.Round(bal / (year * 1.22m) * 365m)) : 0;
        // months: revenue, costs, result and liquidity at the month end
        var liqAcc = groups.Where(g => g.Section == "Liquidità").SelectMany(g => g.Accounts.Select(a => a.Code)).ToHashSet();
        List<BilMonth> Months(int year, int last)
        {
            var list = new List<BilMonth>(); if (!pl.Any(l => l.Month.StartsWith($"{year:D4}-", StringComparison.Ordinal)) && !hasBal) return list;
            for (var m = 1; m <= last; m++)
            {
                var k = M(year, m); var r = Math.Round(pl.Where(l => l.Kind == "R" && l.Month == k).Sum(l => l.Amount), 2); var c = Math.Round(pl.Where(l => l.Kind == "C" && l.Month == k).Sum(l => l.Amount), 2);
                var l = hasBal ? Math.Round(Bal(year, k).Where(x => liqAcc.Contains(x.Key)).Sum(x => x.Value), 2) : 0;
                list.Add(new BilMonth(k, r, c, r - c, l));
            }
            return list;
        }
        return new Bilancio(DateTimeOffset.Now.ToString("O"), curM, M(y - 1, 12), pl.Count > 0, hasBal, note,
            rev, cost, rev - cost, revP, costP, revP - costP, revLY, revLY - costLY,
            income, assets, liabilities,
            Sec(assets, _ => true), Sec(liabilities, _ => true), Sec(assets, _ => true, true), Sec(liabilities, _ => true, true),
            liq, liqP, fin, liq - fin, liqP - finP, recv, recvP, pay, payP, vat, equity, equityP,
            Days(recv, rev12), Days(pay, buy12), rev12, buy12, Months(y, today.Month), Months(y - 1, 12));
    }

    static string Pretty(string s)
    {
        s = (s ?? "").Trim(); if (s.Length == 0) return s;
        var low = s.ToLowerInvariant();
        return s == s.ToUpperInvariant() ? char.ToUpper(low[0], CultureInfo.GetCultureInfo("it-IT")) + low[1..] : s;
    }

    /// The statements as a CSV for the accountant (semicolon, Italian decimals, opens straight in Excel).
    public string BilancioCsv()
    {
        var b = Bilancio(); var it = CultureInfo.GetCultureInfo("it-IT"); var sb = new StringBuilder();
        string N(decimal v) => v.ToString("0.00", it);
        string Q(string s) => "\"" + (s ?? "").Replace("\"", "\"\"") + "\"";
        sb.AppendLine($"Prospetto;Sezione;Gruppo;Conto;Codice;{b.Month};Confronto");
        foreach (var g in b.Income) foreach (var a in g.Accounts) sb.AppendLine($"Conto economico;{Q(g.Section)};{Q(g.Label)};{Q(a.Label)};{Q(a.Code)};{N(a.Amount)};{N(a.Prev)}");
        sb.AppendLine($"Conto economico;Risultato;;Ricavi meno costi;;{N(b.Result)};{N(b.ResultPrev)}");
        foreach (var (side, list) in new[] { ("Attivo", b.Assets), ("Passivo", b.Liabilities) })
            foreach (var g in list) foreach (var a in g.Accounts) sb.AppendLine($"Stato patrimoniale {side};{Q(g.Section)};{Q(g.Label)};{Q(a.Label)};{Q(a.Code)};{N(a.Amount)};{N(a.Prev)}");
        sb.AppendLine($"Stato patrimoniale;Totale attivo;;;;{N(b.TotalAssets)};{N(b.TotalAssetsPrev)}");
        sb.AppendLine($"Stato patrimoniale;Totale passivo e netto;;;;{N(b.TotalLiabilities)};{N(b.TotalLiabilitiesPrev)}");
        return sb.ToString();
    }
}
