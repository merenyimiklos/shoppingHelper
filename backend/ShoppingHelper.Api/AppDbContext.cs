using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace ShoppingHelper.Api;

public sealed class AppDbContext(DbContextOptions<AppDbContext> options)
    : IdentityDbContext<AppUser, IdentityRole<Guid>, Guid>(options)
{
    public DbSet<Household> Households => Set<Household>();
    public DbSet<HouseholdMember> HouseholdMembers => Set<HouseholdMember>();
    public DbSet<HouseholdInvite> HouseholdInvites => Set<HouseholdInvite>();
    public DbSet<ShoppingList> ShoppingLists => Set<ShoppingList>();
    public DbSet<ShoppingItem> ShoppingItems => Set<ShoppingItem>();
    public DbSet<ProductOffer> ProductOffers => Set<ProductOffer>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);

        builder.Entity<AppUser>(entity =>
        {
            entity.Property(x => x.DisplayName).HasMaxLength(100).IsRequired();
        });

        builder.Entity<Household>(entity =>
        {
            entity.Property(x => x.Name).HasMaxLength(120).IsRequired();
            entity.HasOne<AppUser>().WithMany().HasForeignKey(x => x.OwnerId).OnDelete(DeleteBehavior.Restrict);
        });

        builder.Entity<HouseholdMember>(entity =>
        {
            entity.HasKey(x => new { x.HouseholdId, x.UserId });
            entity.Property(x => x.Role).HasMaxLength(30).IsRequired();
            entity.HasOne(x => x.Household).WithMany(x => x.Members).HasForeignKey(x => x.HouseholdId).OnDelete(DeleteBehavior.Cascade);
            entity.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<HouseholdInvite>(entity =>
        {
            entity.HasIndex(x => x.Code).IsUnique();
            entity.Property(x => x.Code).HasMaxLength(16).IsRequired();
            entity.HasOne(x => x.Household).WithMany().HasForeignKey(x => x.HouseholdId).OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<ShoppingList>(entity =>
        {
            entity.Property(x => x.Name).HasMaxLength(120).IsRequired();
            entity.HasOne(x => x.Household).WithMany(x => x.Lists).HasForeignKey(x => x.HouseholdId).OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<ShoppingItem>(entity =>
        {
            entity.Property(x => x.Name).HasMaxLength(240).IsRequired();
            entity.Property(x => x.Unit).HasMaxLength(24).IsRequired();
            entity.Property(x => x.Note).HasMaxLength(500);
            entity.Property(x => x.Quantity).HasPrecision(10, 3);
            entity.HasIndex(x => new { x.ShoppingListId, x.Position });
            entity.HasOne(x => x.ShoppingList).WithMany(x => x.Items).HasForeignKey(x => x.ShoppingListId).OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<ProductOffer>(entity =>
        {
            entity.Property(x => x.Store).HasMaxLength(80).IsRequired();
            entity.Property(x => x.ProductName).HasMaxLength(500).IsRequired();
            entity.Property(x => x.NormalizedName).HasMaxLength(500).IsRequired();
            entity.Property(x => x.Brand).HasMaxLength(120);
            entity.Property(x => x.PackageSize).HasMaxLength(120);
            entity.Property(x => x.Price).HasPrecision(12, 2);
            entity.Property(x => x.UnitPrice).HasPrecision(12, 2);
            entity.HasIndex(x => x.NormalizedName);
            entity.HasIndex(x => new { x.Store, x.PriceDate });
            entity.HasIndex(x => new { x.Source, x.ExternalId, x.Store }).IsUnique().HasFilter("\"ExternalId\" IS NOT NULL");
        });
    }
}
