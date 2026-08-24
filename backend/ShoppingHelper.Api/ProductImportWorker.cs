using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;

namespace ShoppingHelper.Api;

public sealed class ProductImportWorker(
    IServiceScopeFactory scopeFactory,
    IHttpClientFactory httpClientFactory,
    IConfiguration configuration,
    ILogger<ProductImportWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(15), stoppingToken);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await ImportOnce(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Product import failed.");
            }

            await Task.Delay(TimeSpan.FromHours(24), stoppingToken);
        }
    }

    private async Task ImportOnce(CancellationToken cancellationToken)
    {
        var url = configuration["ProductImport:DailyDataUrl"];
        if (string.IsNullOrWhiteSpace(url))
        {
            logger.LogInformation("ProductImport:DailyDataUrl is empty; automatic price import is disabled.");
            return;
        }

        var client = httpClientFactory.CreateClient("product-import");
        using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();

        await using var source = await response.Content.ReadAsStreamAsync(cancellationToken);
        var rows = await ReadRows(source, url, response.Content.Headers.ContentType?.MediaType, cancellationToken);
        if (rows.Count == 0)
        {
            logger.LogWarning("Price import returned no recognizable rows from {Url}.", url);
            return;
        }

        using var scope = scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var today = DateOnly.FromDateTime(DateTime.UtcNow);
        var existing = await db.ProductOffers
            .Where(x => x.PriceDate >= today.AddDays(-2))
            .ToListAsync(cancellationToken);
        var byKey = existing.ToDictionary(BuildKey, StringComparer.OrdinalIgnoreCase);

        var imported = 0;
        foreach (var row in rows)
        {
            var offer = Map(row, today);
            if (offer is null) continue;

            var key = BuildKey(offer);
            if (byKey.TryGetValue(key, out var current))
            {
                current.ProductName = offer.ProductName;
                current.NormalizedName = offer.NormalizedName;
                current.Brand = offer.Brand;
                current.PackageSize = offer.PackageSize;
                current.Price = offer.Price;
                current.UnitPrice = offer.UnitPrice;
                current.UnitPriceUnit = offer.UnitPriceUnit;
                current.ImageUrl = offer.ImageUrl ?? current.ImageUrl;
                current.ProductUrl = offer.ProductUrl ?? current.ProductUrl;
                current.PriceDate = offer.PriceDate;
                current.FetchedAt = DateTimeOffset.UtcNow;
            }
            else
            {
                db.ProductOffers.Add(offer);
                byKey[key] = offer;
            }
            imported++;
        }

        await db.SaveChangesAsync(cancellationToken);
        await db.ProductOffers.Where(x => x.PriceDate < today.AddDays(-45)).ExecuteDeleteAsync(cancellationToken);
        logger.LogInformation("Imported or updated {Count} product offers.", imported);
    }

    private static string BuildKey(ProductOffer offer)
    {
        var identity = !string.IsNullOrWhiteSpace(offer.ExternalId)
            ? offer.ExternalId
            : $"{offer.NormalizedName}|{offer.PackageSize}";
        return $"{offer.Source}|{offer.Store}|{identity}|{offer.PriceDate:yyyy-MM-dd}";
    }

    private static ProductOffer? Map(Dictionary<string, string?> row, DateOnly fallbackDate)
    {
        string? Get(params string[] names)
        {
            foreach (var name in names)
            {
                var key = NormalizeHeader(name);
                if (row.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value)) return value.Trim();
            }
            return null;
        }

        var name = Get("productname", "product_name", "termeknev", "termek_nev", "name", "megnevezes");
        var store = Get("retailer", "retailername", "retailer_name", "kereskedo", "uzletlanc", "bolt", "store", "chain", "shop");
        var priceRaw = Get("price", "actualprice", "actual_price", "bruttoar", "brutto_ar", "ar", "grossprice", "gross_price", "minprice", "min_price");
        if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(store) || !TryMoney(priceRaw, out var price) || price <= 0)
            return null;

        var unitPriceRaw = Get("unitprice", "unit_price", "egysegar", "egyseg_ar");
        decimal? unitPrice = TryMoney(unitPriceRaw, out var parsedUnitPrice) ? parsedUnitPrice : null;
        var dateRaw = Get("pricedate", "price_date", "datum", "date");
        var priceDate = DateOnly.TryParse(dateRaw, CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsedDate)
            ? parsedDate
            : fallbackDate;

        return new ProductOffer
        {
            Source = Get("source", "forras") ?? "GVH",
            ExternalId = Get("ean", "gtin", "barcode", "vonalkod", "productid", "product_id", "id", "cikkszam"),
            Store = CanonicalStore(store),
            ProductName = name,
            NormalizedName = TextNormalizer.NormalizeSearch(name),
            Brand = Get("brand", "marka"),
            PackageSize = Get("packagesize", "package_size", "kiszereles", "quantity", "mennyiseg"),
            Price = price,
            UnitPrice = unitPrice,
            UnitPriceUnit = Get("unitpriceunit", "unit_price_unit", "egyseg", "unit"),
            ImageUrl = Get("imageurl", "image_url", "kepurl", "kep_url", "image"),
            ProductUrl = Get("producturl", "product_url", "url", "link"),
            PriceDate = priceDate,
            FetchedAt = DateTimeOffset.UtcNow
        };
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

    private static bool TryMoney(string? value, out decimal result)
    {
        result = 0;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var cleaned = value.Replace("Ft", "", StringComparison.OrdinalIgnoreCase)
            .Replace("HUF", "", StringComparison.OrdinalIgnoreCase)
            .Replace("\u00A0", "")
            .Replace(" ", "")
            .Replace(',', '.');
        var numeric = new string(cleaned.TakeWhile(c => char.IsDigit(c) || c is '.' or '-').ToArray());
        return decimal.TryParse(numeric, NumberStyles.Number, CultureInfo.InvariantCulture, out result);
    }

    private static async Task<List<Dictionary<string, string?>>> ReadRows(
        Stream source,
        string url,
        string? mediaType,
        CancellationToken cancellationToken)
    {
        var buffer = new MemoryStream();
        await source.CopyToAsync(buffer, cancellationToken);
        buffer.Position = 0;

        if (url.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) || mediaType?.Contains("zip", StringComparison.OrdinalIgnoreCase) == true)
        {
            using var archive = new ZipArchive(buffer, ZipArchiveMode.Read, leaveOpen: true);
            var entry = archive.Entries.FirstOrDefault(x => x.Name.EndsWith(".json", StringComparison.OrdinalIgnoreCase))
                        ?? archive.Entries.FirstOrDefault(x => x.Name.EndsWith(".csv", StringComparison.OrdinalIgnoreCase));
            if (entry is null) return [];
            await using var entryStream = entry.Open();
            var copy = new MemoryStream();
            await entryStream.CopyToAsync(copy, cancellationToken);
            copy.Position = 0;
            return entry.Name.EndsWith(".json", StringComparison.OrdinalIgnoreCase)
                ? await ReadJson(copy, cancellationToken)
                : await ReadCsv(copy, cancellationToken);
        }

        if (url.EndsWith(".json", StringComparison.OrdinalIgnoreCase) || mediaType?.Contains("json", StringComparison.OrdinalIgnoreCase) == true)
            return await ReadJson(buffer, cancellationToken);

        return await ReadCsv(buffer, cancellationToken);
    }

    private static async Task<List<Dictionary<string, string?>>> ReadJson(Stream stream, CancellationToken cancellationToken)
    {
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        var result = new List<Dictionary<string, string?>>();
        Walk(document.RootElement, result);
        return result;

        static void Walk(JsonElement element, List<Dictionary<string, string?>> rows)
        {
            if (element.ValueKind == JsonValueKind.Object)
            {
                var row = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
                foreach (var property in element.EnumerateObject())
                {
                    if (property.Value.ValueKind is JsonValueKind.String or JsonValueKind.Number or JsonValueKind.True or JsonValueKind.False or JsonValueKind.Null)
                        row[NormalizeHeader(property.Name)] = property.Value.ValueKind == JsonValueKind.Null ? null : property.Value.ToString();
                    else
                        Walk(property.Value, rows);
                }
                if (row.Count >= 3) rows.Add(row);
            }
            else if (element.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in element.EnumerateArray()) Walk(item, rows);
            }
        }
    }

    private static async Task<List<Dictionary<string, string?>>> ReadCsv(Stream stream, CancellationToken cancellationToken)
    {
        stream.Position = 0;
        using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true, leaveOpen: true);
        var first = await reader.ReadLineAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(first)) return [];
        var delimiter = DetectDelimiter(first);
        var headers = ParseCsvLine(first, delimiter).Select(NormalizeHeader).ToArray();
        var result = new List<Dictionary<string, string?>>();
        string? line;
        while ((line = await reader.ReadLineAsync(cancellationToken)) is not null)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            var values = ParseCsvLine(line, delimiter);
            var row = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < Math.Min(headers.Length, values.Count); i++) row[headers[i]] = values[i];
            result.Add(row);
        }
        return result;
    }

    private static char DetectDelimiter(string line)
    {
        var options = new[] { ';', ',', '\t' };
        return options.OrderByDescending(c => line.Count(x => x == c)).First();
    }

    private static List<string> ParseCsvLine(string line, char delimiter)
    {
        var values = new List<string>();
        var current = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (c == '"')
            {
                if (quoted && i + 1 < line.Length && line[i + 1] == '"')
                {
                    current.Append('"');
                    i++;
                }
                else quoted = !quoted;
            }
            else if (c == delimiter && !quoted)
            {
                values.Add(current.ToString().Trim());
                current.Clear();
            }
            else current.Append(c);
        }
        values.Add(current.ToString().Trim());
        return values;
    }

    private static string NormalizeHeader(string value) => new string(
        TextNormalizer.NormalizeSearch(value).Where(char.IsLetterOrDigit).ToArray());
}
