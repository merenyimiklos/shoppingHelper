using Microsoft.AspNetCore.Identity;

namespace ShoppingHelper.Api;

public sealed class AppUser : IdentityUser<Guid>
{
    public string DisplayName { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class Household
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public Guid OwnerId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public List<HouseholdMember> Members { get; set; } = [];
    public List<ShoppingList> Lists { get; set; } = [];
}

public sealed class HouseholdMember
{
    public Guid HouseholdId { get; set; }
    public Household Household { get; set; } = null!;
    public Guid UserId { get; set; }
    public AppUser User { get; set; } = null!;
    public string Role { get; set; } = "member";
    public DateTimeOffset JoinedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class HouseholdInvite
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid HouseholdId { get; set; }
    public Household Household { get; set; } = null!;
    public string Code { get; set; } = string.Empty;
    public Guid CreatedByUserId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset? UsedAt { get; set; }
    public Guid? UsedByUserId { get; set; }
}

public sealed class ShoppingList
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid HouseholdId { get; set; }
    public Household Household { get; set; } = null!;
    public string Name { get; set; } = string.Empty;
    public bool IsArchived { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public List<ShoppingItem> Items { get; set; } = [];
}

public sealed class ShoppingItem
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid ShoppingListId { get; set; }
    public ShoppingList ShoppingList { get; set; } = null!;
    public string Name { get; set; } = string.Empty;
    public decimal Quantity { get; set; } = 1;
    public string Unit { get; set; } = "db";
    public string? Note { get; set; }
    public bool IsChecked { get; set; }
    public int Position { get; set; }
    public Guid AddedByUserId { get; set; }
    public Guid? CheckedByUserId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class ProductOffer
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Source { get; set; } = "GVH";
    public string? ExternalId { get; set; }
    public string Store { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string NormalizedName { get; set; } = string.Empty;
    public string? Brand { get; set; }
    public string? PackageSize { get; set; }
    public decimal Price { get; set; }
    public decimal? UnitPrice { get; set; }
    public string? UnitPriceUnit { get; set; }
    public string? ImageUrl { get; set; }
    public string? ProductUrl { get; set; }
    public DateOnly PriceDate { get; set; } = DateOnly.FromDateTime(DateTime.UtcNow);
    public DateTimeOffset FetchedAt { get; set; } = DateTimeOffset.UtcNow;
}

public sealed record RegisterRequest(string Email, string Password, string DisplayName);
public sealed record LoginRequest(string Email, string Password);
public sealed record AuthResponse(string Token, Guid UserId, string DisplayName, string Email);
public sealed record CreateHouseholdRequest(string Name);
public sealed record JoinHouseholdRequest(string Code);
public sealed record CreateListRequest(string Name);
public sealed record CreateItemRequest(string Name, decimal Quantity = 1, string Unit = "db", string? Note = null);
public sealed record UpdateItemRequest(string? Name = null, decimal? Quantity = null, string? Unit = null, string? Note = null, bool? IsChecked = null, int? Position = null);

public sealed record HouseholdDto(Guid Id, string Name, string Role, Guid OwnerId, int MemberCount);
public sealed record InviteDto(string Code, DateTimeOffset ExpiresAt);
public sealed record ShoppingListSummaryDto(Guid Id, string Name, int OpenItems, int TotalItems, DateTimeOffset UpdatedAt);
public sealed record ShoppingItemDto(Guid Id, string Name, decimal Quantity, string Unit, string? Note, bool IsChecked, int Position, Guid AddedByUserId, Guid? CheckedByUserId, DateTimeOffset UpdatedAt);
public sealed record ShoppingListDto(Guid Id, Guid HouseholdId, string Name, IReadOnlyList<ShoppingItemDto> Items, DateTimeOffset UpdatedAt);
public sealed record ProductOfferDto(Guid Id, string Store, string ProductName, string? Brand, string? PackageSize, decimal Price, decimal? UnitPrice, string? UnitPriceUnit, string? ImageUrl, string? ProductUrl, DateOnly PriceDate);

public sealed record BasketLineDto(
    Guid ItemId,
    string Query,
    decimal Quantity,
    string Unit,
    string Store,
    bool Matched,
    string? ProductName,
    string? PackageSize,
    decimal? PackagePrice,
    decimal? UnitPrice,
    string? UnitPriceUnit,
    decimal? EstimatedTotal,
    string? ImageUrl,
    string? ProductUrl,
    DateOnly? PriceDate);

public sealed record StoreBasketDto(
    string Store,
    decimal EstimatedTotal,
    int MatchedItems,
    int MissingItems,
    IReadOnlyList<BasketLineDto> Lines);

public sealed record BasketComparisonDto(
    Guid ListId,
    DateTimeOffset GeneratedAt,
    IReadOnlyList<StoreBasketDto> Stores);
