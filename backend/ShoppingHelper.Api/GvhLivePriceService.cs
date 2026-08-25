using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using HtmlAgilityPack;
using Microsoft.Extensions.Caching.Memory;

namespace ShoppingHelper.Api;

/// <summary>
/// Optional, user-initiated live lookup against the public Árfigyelő search pages.
/// It is intentionally isolated behind configuration so the app can fall back to
/// the imported daily dataset if the public website changes or live lookup is disabled.
/// </summary>
public sealed class GvhLivePriceService(
    IHttpClientFactory httpClientFactory,
    IConfiguration configuration,
    IMemoryCache cache,
    ILogger<GvhLivePriceService> logger)
{
    private static readonly Regex MoneyRegex = new(@"(?<amount>\d[\d\s\u00A0.]*)\s*Ft(?:-tól)?", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex UnitPriceRegex = new(@"(?<amount>\d[\d\s\u00A0.]*)\s*Ft\s*/\s*(?<unit>kg|l|db)", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex PackageRegex = new(@"(?<!\d)(?<amount>\d+(?:[.,]\d+)?)\s*(?<unit>kg|g|l|ml|db)\b", RegexOptions.Compiled | RegexOptions.IgnoreCase);

    public async Task<IReadOnlyList<ProductOfferDto>> SearchAsync(
        string query,
        IReadOnlyCollection<string>? stores,
        CancellationToken cancellationToken)
    {
        if (!IsEnabled() || string.IsNullOrWhiteSpace(query)) return [];

        var requestedStores = (stores ?? Array.Empty<string>())
            .Select(CanonicalStore)
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var cacheKey = $"gvh-live:{TextNormalizer.NormalizeSearch(query)}:{string.Join(',', requestedStores.OrderBy(x => x))}";
        if (cache.TryGetValue(cacheKey, out IReadOnlyList<ProductOfferDto>? cached) && cached is not null)
            return cached;

        var baseUrl = (configuration["GvhLiveSearch:BaseUrl"] ?? "https://arfigyelo.gvh.hu").TrimEnd('/');
        var escaped = Uri.EscapeDataString(query.Trim()).Replace("%20", "+", StringComparison.Ordinal);
        var url = $"{baseUrl}/kereses/{escaped}?order=relevance";

        try
        {
            var client = httpClientFactory.CreateClient("gvh-live");
            using var response = await client.GetAsync(url, cancellationToken);
            response.EnsureSuccessStatusCode();
            var html = await response.Content.ReadAsStringAsync(cancellationToken);
            var offers = Parse(html, baseUrl, requestedStores);
            cache.Set(cacheKey, offers, TimeSpan.FromMinutes(30));
            return offers;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            logger.LogWarning(ex, "Live Árfigyelő lookup failed for query {Query}.", query);
            return [];
        }
    }

    private bool IsEnabled()
    {
        var value = configuration["GvhLiveSearch:Enabled"];
        return !bool.TryParse(value, out var enabled) || enabled;
    }

    private static IReadOnlyList<ProductOfferDto> Parse(string html, string baseUrl, IReadOnlyCollection<string> requestedStores)
    {
        var document = new HtmlDocument();
        document.LoadHtml(html);
        var productImages = document.DocumentNode.SelectNodes("//img[@alt]")
            ?.Where(x => HtmlEntity.DeEntitize(x.GetAttributeValue("alt", string.Empty))
                .Contains("mintakép", StringComparison.OrdinalIgnoreCase))
            .ToList() ?? [];

        var result = new List<ProductOfferDto>();
        foreach (var productImage in productImages)
        {
            var rawAlt = Collapse(HtmlEntity.DeEntitize(productImage.GetAttributeValue("alt", string.Empty)));
            var productName = Regex.Replace(rawAlt, @"\s*mintakép\s*$", string.Empty, RegexOptions.IgnoreCase).Trim();
            if (string.IsNullOrWhiteSpace(productName)) continue;

            var card = FindProductContainer(productImage);
            if (card is null) continue;

            var cardText = Collapse(HtmlEntity.DeEntitize(card.InnerText));
            var packageSize = ExtractPackage(cardText);
            var (unitPrice, unitPriceUnit) = ExtractUnitPrice(cardText);
            var imageUrl = ResolveUrl(baseUrl, FirstUsableImageUrl(productImage));
            var productLink = card.SelectSingleNode(".//a[contains(@href, '/t/')]")?.GetAttributeValue("href", null);
            var productUrl = ResolveUrl(baseUrl, productLink);

            string? pendingStore = null;
            foreach (var node in card.DescendantsAndSelf())
            {
                if (node.Name.Equals("img", StringComparison.OrdinalIgnoreCase))
                {
                    var alt = Collapse(HtmlEntity.DeEntitize(node.GetAttributeValue("alt", string.Empty)));
                    var store = StoreFromLogoAlt(alt);
                    if (store is not null) pendingStore = store;
                    continue;
                }

                if (pendingStore is null || node.NodeType != HtmlNodeType.Text) continue;
                var text = Collapse(HtmlEntity.DeEntitize(node.InnerText));
                if (string.IsNullOrWhiteSpace(text) || text.Contains("Egységár", StringComparison.OrdinalIgnoreCase)) continue;
                var money = MoneyRegex.Match(text);
                if (!money.Success || !TryMoney(money.Groups["amount"].Value, out var price) || price <= 0) continue;

                if (requestedStores.Count == 0 || requestedStores.Contains(pendingStore, StringComparer.OrdinalIgnoreCase))
                {
                    result.Add(new ProductOfferDto(
                        DeterministicGuid($"GVH-LIVE|{pendingStore}|{productName}|{packageSize}|{price}"),
                        pendingStore,
                        productName,
                        null,
                        packageSize,
                        price,
                        unitPrice,
                        unitPriceUnit,
                        imageUrl,
                        productUrl,
                        DateOnly.FromDateTime(DateTime.UtcNow)));
                }
                pendingStore = null;
            }
        }

        return result
            .GroupBy(x => $"{x.Store}\u001f{x.ProductName}\u001f{x.PackageSize}\u001f{x.Price}", StringComparer.OrdinalIgnoreCase)
            .Select(x => x.First())
            .OrderBy(x => x.Price)
            .ThenBy(x => x.Store)
            .Take(50)
            .ToList();
    }

    private static HtmlNode? FindProductContainer(HtmlNode image)
    {
        var node = image.ParentNode;
        for (var depth = 0; depth < 9 && node is not null; depth++, node = node.ParentNode)
        {
            var hasStoreLogo = node.SelectNodes(".//img[@alt]")?.Any(x => StoreFromLogoAlt(x.GetAttributeValue("alt", string.Empty)) is not null) == true;
            var text = HtmlEntity.DeEntitize(node.InnerText);
            var hasPrice = MoneyRegex.IsMatch(text);
            var hasProductLink = node.SelectSingleNode(".//a[contains(@href, '/t/')]") is not null;
            if (hasStoreLogo && hasPrice && hasProductLink) return node;
        }
        return null;
    }

    private static string? FirstUsableImageUrl(HtmlNode image)
    {
        foreach (var attribute in new[] { "data-src", "data-lazy-src", "src" })
        {
            var value = image.GetAttributeValue(attribute, null);
            if (!string.IsNullOrWhiteSpace(value) && !value.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
                return value;
        }

        var srcSet = image.GetAttributeValue("srcset", null);
        return srcSet?.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .LastOrDefault()?.Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
    }

    private static string? ExtractPackage(string text)
    {
        var match = PackageRegex.Match(text);
        if (!match.Success) return null;
        var amount = match.Groups["amount"].Value.Replace(',', '.');
        return $"{amount} {match.Groups["unit"].Value.ToLowerInvariant()}";
    }

    private static (decimal? Price, string? Unit) ExtractUnitPrice(string text)
    {
        var match = UnitPriceRegex.Match(text);
        if (!match.Success || !TryMoney(match.Groups["amount"].Value, out var price)) return (null, null);
        return (price, match.Groups["unit"].Value.ToLowerInvariant());
    }

    private static string? StoreFromLogoAlt(string alt)
    {
        var normalized = TextNormalizer.NormalizeSearch(alt);
        if (!normalized.Contains("logo")) return null;
        if (normalized.Contains("lidl")) return "Lidl";
        if (normalized.Contains("spar")) return "SPAR";
        if (normalized.Contains("aldi")) return "Aldi";
        if (normalized.Contains("penny")) return "Penny";
        if (normalized.Contains("tesco")) return "Tesco";
        if (normalized.Contains("auchan")) return "Auchan";
        if (normalized.Contains("rossmann")) return "Rossmann";
        if (normalized.Contains("muller")) return "Müller";
        if (normalized.Contains("dm")) return "dm";
        return null;
    }

    private static string CanonicalStore(string value)
    {
        var normalized = TextNormalizer.NormalizeSearch(value);
        if (normalized.Contains("lidl")) return "Lidl";
        if (normalized.Contains("spar")) return "SPAR";
        if (normalized.Contains("aldi")) return "Aldi";
        if (normalized.Contains("penny")) return "Penny";
        if (normalized.Contains("tesco")) return "Tesco";
        if (normalized.Contains("auchan")) return "Auchan";
        if (normalized.Contains("rossmann")) return "Rossmann";
        if (normalized.Contains("muller")) return "Müller";
        if (normalized == "dm" || normalized.Contains("dm drogerie")) return "dm";
        return value.Trim();
    }

    private static string? ResolveUrl(string baseUrl, string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        if (Uri.TryCreate(value, UriKind.Absolute, out var absolute)) return absolute.ToString();
        return Uri.TryCreate(new Uri(baseUrl + "/"), value, out var resolved) ? resolved.ToString() : null;
    }

    private static bool TryMoney(string value, out decimal result)
    {
        var cleaned = value.Replace("\u00A0", string.Empty).Replace(" ", string.Empty).Replace(".", string.Empty).Replace(',', '.');
        return decimal.TryParse(cleaned, NumberStyles.Number, CultureInfo.InvariantCulture, out result);
    }

    private static Guid DeterministicGuid(string value)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return new Guid(hash.AsSpan(0, 16));
    }

    private static string Collapse(string value) => Regex.Replace(value, @"\s+", " ").Trim();
}
