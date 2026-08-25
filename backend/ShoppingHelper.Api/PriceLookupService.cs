using Microsoft.EntityFrameworkCore;

namespace ShoppingHelper.Api;

public sealed class PriceLookupService(AppDbContext db, GvhLivePriceService live)
{
    public async Task<IReadOnlyList<ProductOfferDto>> SearchAsync(
        string query,
        string? stores,
        CancellationToken cancellationToken)
    {
        var normalized = TextNormalizer.NormalizeSearch(query);
        if (normalized.Length < 2) return [];

        var storeList = ParseStores(stores);
        var imported = await SearchImportedAsync(normalized, storeList, cancellationToken);
        var liveOffers = await live.SearchAsync(query, storeList, cancellationToken);

        return liveOffers
            .Concat(imported)
            .GroupBy(
                x => $"{x.Store}\u001f{TextNormalizer.NormalizeSearch(x.ProductName)}\u001f{x.PackageSize}\u001f{x.Price}",
                StringComparer.OrdinalIgnoreCase)
            .Select(x => x.First())
            .OrderBy(x => x.Price)
            .ThenBy(x => x.Store)
            .Take(50)
            .ToList();
    }

    public async Task<BasketComparisonDto?> CompareListAsync(
        Guid listId,
        Guid userId,
        string? stores,
        CancellationToken cancellationToken)
    {
        var list = await db.ShoppingLists
            .AsNoTracking()
            .Include(x => x.Items)
            .FirstOrDefaultAsync(
                x => x.Id == listId && x.Household.Members.Any(m => m.UserId == userId),
                cancellationToken);
        if (list is null) return null;

        var storeList = ParseStores(stores);
        if (storeList.Length == 0) storeList = ["Lidl", "SPAR"];

        var openItems = list.Items
            .Where(x => !x.IsChecked)
            .OrderBy(x => x.Position)
            .ThenBy(x => x.CreatedAt)
            .Take(40)
            .ToList();

        var offersByItem = new Dictionary<Guid, IReadOnlyList<ProductOfferDto>>();
        foreach (var item in openItems)
        {
            cancellationToken.ThrowIfCancellationRequested();
            offersByItem[item.Id] = await SearchAsync(item.Name, string.Join(',', storeList), cancellationToken);
        }

        var storesResult = new List<StoreBasketDto>();
        foreach (var store in storeList)
        {
            var lines = new List<BasketLineDto>();
            foreach (var item in openItems)
            {
                var offer = offersByItem[item.Id]
                    .Where(x => x.Store.Equals(store, StringComparison.OrdinalIgnoreCase))
                    .OrderBy(x => EffectiveComparisonPrice(item, x))
                    .ThenBy(x => x.Price)
                    .FirstOrDefault();

                if (offer is null)
                {
                    lines.Add(new BasketLineDto(
                        item.Id, item.Name, item.Quantity, item.Unit, store, false,
                        null, null, null, null, null, null, null, null, null));
                    continue;
                }

                var estimated = EstimateTotal(item, offer);
                lines.Add(new BasketLineDto(
                    item.Id,
                    item.Name,
                    item.Quantity,
                    item.Unit,
                    store,
                    true,
                    offer.ProductName,
                    offer.PackageSize,
                    offer.Price,
                    offer.UnitPrice,
                    offer.UnitPriceUnit,
                    estimated,
                    offer.ImageUrl,
                    offer.ProductUrl,
                    offer.PriceDate));
            }

            storesResult.Add(new StoreBasketDto(
                store,
                lines.Where(x => x.EstimatedTotal is not null).Sum(x => x.EstimatedTotal!.Value),
                lines.Count(x => x.Matched),
                lines.Count(x => !x.Matched),
                lines));
        }

        var ordered = storesResult
            .OrderBy(x => x.MissingItems)
            .ThenBy(x => x.EstimatedTotal)
            .ToList();

        return new BasketComparisonDto(listId, DateTimeOffset.UtcNow, ordered);
    }

    private async Task<IReadOnlyList<ProductOfferDto>> SearchImportedAsync(
        string normalized,
        string[] stores,
        CancellationToken cancellationToken)
    {
        var latestDate = await db.ProductOffers.MaxAsync(x => (DateOnly?)x.PriceDate, cancellationToken);
        if (latestDate is null) return [];

        var query = db.ProductOffers.AsNoTracking()
            .Where(x => x.PriceDate == latestDate && EF.Functions.ILike(x.NormalizedName, $"%{normalized}%"));
        if (stores.Length > 0) query = query.Where(x => stores.Contains(x.Store));

        return await query
            .OrderBy(x => x.Price)
            .ThenBy(x => x.Store)
            .Take(50)
            .Select(x => new ProductOfferDto(
                x.Id, x.Store, x.ProductName, x.Brand, x.PackageSize, x.Price,
                x.UnitPrice, x.UnitPriceUnit, x.ImageUrl, x.ProductUrl, x.PriceDate))
            .ToListAsync(cancellationToken);
    }

    private static decimal EffectiveComparisonPrice(ShoppingItem item, ProductOfferDto offer) =>
        EstimateTotal(item, offer) ?? offer.Price;

    private static decimal? EstimateTotal(ShoppingItem item, ProductOfferDto offer)
    {
        var quantity = item.Quantity <= 0 ? 1 : item.Quantity;
        var unit = TextNormalizer.NormalizeSearch(item.Unit).Trim();
        var offerUnit = TextNormalizer.NormalizeSearch(offer.UnitPriceUnit ?? string.Empty).Trim();

        if (offer.UnitPrice is { } unitPrice)
        {
            if (unit is "kg" && offerUnit is "kg") return decimal.Round(quantity * unitPrice, 0);
            if (unit is "g" && offerUnit is "kg") return decimal.Round(quantity / 1000m * unitPrice, 0);
            if (unit is "l" or "liter" && offerUnit is "l") return decimal.Round(quantity * unitPrice, 0);
            if (unit is "ml" && offerUnit is "l") return decimal.Round(quantity / 1000m * unitPrice, 0);
            if (unit is "db" && offerUnit is "db") return decimal.Round(quantity * unitPrice, 0);
        }

        return decimal.Round(offer.Price * quantity, 0);
    }

    private static string[] ParseStores(string? stores)
    {
        var source = string.IsNullOrWhiteSpace(stores) ? "Lidl,SPAR" : stores;
        return source.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(CanonicalStore)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
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
}
