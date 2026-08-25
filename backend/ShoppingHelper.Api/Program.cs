using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;

namespace ShoppingHelper.Api;

public partial class Program
{
    public static async Task Main(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);

        var connectionString = builder.Configuration.GetConnectionString("Default")
            ?? throw new InvalidOperationException("ConnectionStrings:Default is missing.");
        builder.Services.AddDbContext<AppDbContext>(options => options.UseNpgsql(connectionString));

        builder.Services.AddIdentityCore<AppUser>(options =>
            {
                options.Password.RequiredLength = 8;
                options.Password.RequireDigit = true;
                options.Password.RequireUppercase = false;
                options.Password.RequireLowercase = true;
                options.Password.RequireNonAlphanumeric = false;
                options.User.RequireUniqueEmail = true;
            })
            .AddEntityFrameworkStores<AppDbContext>();

        var jwtKey = builder.Configuration["Jwt:Key"] ?? "CHANGE-ME-TO-A-LONG-RANDOM-SECRET-KEY";
        var jwtIssuer = builder.Configuration["Jwt:Issuer"] ?? "ShoppingHelper";
        var jwtAudience = builder.Configuration["Jwt:Audience"] ?? "ShoppingHelper.Android";
        builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(options =>
            {
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = jwtIssuer,
                    ValidateAudience = true,
                    ValidAudience = jwtAudience,
                    ValidateIssuerSigningKey = true,
                    IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtKey)),
                    ValidateLifetime = true,
                    ClockSkew = TimeSpan.FromMinutes(2)
                };
                options.Events = new JwtBearerEvents
                {
                    OnMessageReceived = context =>
                    {
                        var token = context.Request.Query["access_token"].ToString();
                        if (!string.IsNullOrWhiteSpace(token) && context.HttpContext.Request.Path.StartsWithSegments("/hubs/shopping"))
                            context.Token = token;
                        return Task.CompletedTask;
                    }
                };
            });

        builder.Services.AddAuthorization();
        builder.Services.AddSignalR();
        builder.Services.AddMemoryCache();
        builder.Services.AddSingleton<IJwtTokenService, JwtTokenService>();

        builder.Services.AddHttpClient("product-import", client =>
        {
            client.Timeout = TimeSpan.FromMinutes(5);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("ShoppingHelper/1.0 (+self-hosted price importer)");
        });
        builder.Services.AddHttpClient("gvh-live", client =>
        {
            client.Timeout = TimeSpan.FromSeconds(20);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("ShoppingHelper/1.0 (+private self-hosted shopping assistant)");
            client.DefaultRequestHeaders.Accept.ParseAdd("text/html,application/xhtml+xml");
        });
        builder.Services.AddSingleton<GvhLivePriceService>();
        builder.Services.AddScoped<PriceLookupService>();
        builder.Services.AddHostedService<ProductImportWorker>();
        builder.Services.AddHealthChecks();
        builder.Services.AddCors(options => options.AddDefaultPolicy(policy =>
            policy.AllowAnyHeader().AllowAnyMethod().SetIsOriginAllowed(_ => true).AllowCredentials()));

        var app = builder.Build();
        app.UseCors();
        app.UseAuthentication();
        app.UseAuthorization();

        using (var scope = app.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
            await db.Database.EnsureCreatedAsync();
        }

        app.MapHealthChecks("/health");
        app.MapGet("/", () => Results.Ok(new { service = "ShoppingHelper API", status = "ok" }));
        app.MapHub<ShoppingHub>("/hubs/shopping");

        MapAuth(app);
        MapHouseholds(app);
        MapShopping(app);
        MapProducts(app);

        await app.RunAsync();
    }

    private static void MapAuth(WebApplication app)
    {
        var auth = app.MapGroup("/api/auth");

        auth.MapPost("/register", async (RegisterRequest request, UserManager<AppUser> users, IJwtTokenService tokens) =>
        {
            var email = request.Email.Trim().ToLowerInvariant();
            if (string.IsNullOrWhiteSpace(email) || string.IsNullOrWhiteSpace(request.DisplayName))
                return Results.BadRequest(new { message = "Email and display name are required." });
            if (await users.FindByEmailAsync(email) is not null)
                return Results.Conflict(new { message = "This email address is already registered." });

            var user = new AppUser
            {
                Id = Guid.NewGuid(),
                Email = email,
                UserName = email,
                DisplayName = request.DisplayName.Trim()
            };
            var result = await users.CreateAsync(user, request.Password);
            if (!result.Succeeded)
                return Results.BadRequest(new { message = string.Join(" ", result.Errors.Select(x => x.Description)) });

            return Results.Ok(new AuthResponse(tokens.Create(user), user.Id, user.DisplayName, user.Email!));
        });

        auth.MapPost("/login", async (LoginRequest request, UserManager<AppUser> users, IJwtTokenService tokens) =>
        {
            var user = await users.FindByEmailAsync(request.Email.Trim().ToLowerInvariant());
            if (user is null || !await users.CheckPasswordAsync(user, request.Password))
                return Results.Unauthorized();
            return Results.Ok(new AuthResponse(tokens.Create(user), user.Id, user.DisplayName, user.Email!));
        });
    }

    private static void MapHouseholds(WebApplication app)
    {
        var api = app.MapGroup("/api/households").RequireAuthorization();

        api.MapGet("/", async (System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            var userId = principal.UserId();
            var items = await db.HouseholdMembers
                .Where(x => x.UserId == userId)
                .OrderBy(x => x.Household.Name)
                .Select(x => new HouseholdDto(x.HouseholdId, x.Household.Name, x.Role, x.Household.OwnerId, x.Household.Members.Count))
                .ToListAsync();
            return Results.Ok(items);
        });

        api.MapPost("/", async (CreateHouseholdRequest request, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            if (string.IsNullOrWhiteSpace(request.Name)) return Results.BadRequest(new { message = "Name is required." });
            var userId = principal.UserId();
            var household = new Household { Name = request.Name.Trim(), OwnerId = userId };
            household.Members.Add(new HouseholdMember { Household = household, UserId = userId, Role = "owner" });
            household.Lists.Add(new ShoppingList { Household = household, Name = "Bevásárlólista" });
            db.Households.Add(household);
            await db.SaveChangesAsync();
            return Results.Created($"/api/households/{household.Id}", new HouseholdDto(household.Id, household.Name, "owner", userId, 1));
        });

        api.MapPost("/join", async (JoinHouseholdRequest request, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            var userId = principal.UserId();
            var code = request.Code.Trim().ToUpperInvariant();
            var invite = await db.HouseholdInvites.Include(x => x.Household).ThenInclude(x => x.Members)
                .FirstOrDefaultAsync(x => x.Code == code);
            if (invite is null || invite.ExpiresAt <= DateTimeOffset.UtcNow || invite.UsedAt is not null)
                return Results.BadRequest(new { message = "Invite code is invalid or expired." });
            if (invite.Household.Members.Any(x => x.UserId == userId))
                return Results.Ok(new HouseholdDto(invite.Household.Id, invite.Household.Name, "member", invite.Household.OwnerId, invite.Household.Members.Count));

            invite.Household.Members.Add(new HouseholdMember { UserId = userId, Role = "member" });
            invite.UsedAt = DateTimeOffset.UtcNow;
            invite.UsedByUserId = userId;
            await db.SaveChangesAsync();
            return Results.Ok(new HouseholdDto(invite.Household.Id, invite.Household.Name, "member", invite.Household.OwnerId, invite.Household.Members.Count));
        });

        api.MapPost("/{householdId:guid}/invite", async (Guid householdId, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            var userId = principal.UserId();
            if (!await IsHouseholdMember(db, householdId, userId)) return Results.Forbid();
            string code;
            do code = Convert.ToHexString(RandomNumberGenerator.GetBytes(4));
            while (await db.HouseholdInvites.AnyAsync(x => x.Code == code));

            var invite = new HouseholdInvite
            {
                HouseholdId = householdId,
                CreatedByUserId = userId,
                Code = code,
                ExpiresAt = DateTimeOffset.UtcNow.AddDays(7)
            };
            db.HouseholdInvites.Add(invite);
            await db.SaveChangesAsync();
            return Results.Ok(new InviteDto(invite.Code, invite.ExpiresAt));
        });

        api.MapGet("/{householdId:guid}/lists", async (Guid householdId, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            if (!await IsHouseholdMember(db, householdId, principal.UserId())) return Results.Forbid();
            var lists = await db.ShoppingLists
                .Where(x => x.HouseholdId == householdId && !x.IsArchived)
                .OrderBy(x => x.CreatedAt)
                .Select(x => new ShoppingListSummaryDto(x.Id, x.Name, x.Items.Count(i => !i.IsChecked), x.Items.Count, x.UpdatedAt))
                .ToListAsync();
            return Results.Ok(lists);
        });

        api.MapPost("/{householdId:guid}/lists", async (Guid householdId, CreateListRequest request, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            if (!await IsHouseholdMember(db, householdId, principal.UserId())) return Results.Forbid();
            if (string.IsNullOrWhiteSpace(request.Name)) return Results.BadRequest(new { message = "Name is required." });
            var list = new ShoppingList { HouseholdId = householdId, Name = request.Name.Trim() };
            db.ShoppingLists.Add(list);
            await db.SaveChangesAsync();
            return Results.Created($"/api/lists/{list.Id}", new ShoppingListSummaryDto(list.Id, list.Name, 0, 0, list.UpdatedAt));
        });
    }

    private static void MapShopping(WebApplication app)
    {
        var lists = app.MapGroup("/api/lists").RequireAuthorization();
        var items = app.MapGroup("/api/items").RequireAuthorization();

        lists.MapGet("/{listId:guid}", async (Guid listId, System.Security.Claims.ClaimsPrincipal principal, AppDbContext db) =>
        {
            var userId = principal.UserId();
            var list = await db.ShoppingLists
                .AsNoTracking()
                .Include(x => x.Items)
                .FirstOrDefaultAsync(x => x.Id == listId && x.Household.Members.Any(m => m.UserId == userId));
            if (list is null) return Results.NotFound();
            return Results.Ok(ToDto(list));
        });

        lists.MapGet("/{listId:guid}/price-comparison", async (
            Guid listId,
            string? stores,
            System.Security.Claims.ClaimsPrincipal principal,
            PriceLookupService prices,
            CancellationToken cancellationToken) =>
        {
            var result = await prices.CompareListAsync(listId, principal.UserId(), stores, cancellationToken);
            return result is null ? Results.NotFound() : Results.Ok(result);
        });

        lists.MapPost("/{listId:guid}/items", async (
            Guid listId,
            CreateItemRequest request,
            System.Security.Claims.ClaimsPrincipal principal,
            AppDbContext db,
            IHubContext<ShoppingHub> hub) =>
        {
            if (string.IsNullOrWhiteSpace(request.Name)) return Results.BadRequest(new { message = "Item name is required." });
            var userId = principal.UserId();
            var list = await db.ShoppingLists.FirstOrDefaultAsync(x => x.Id == listId && x.Household.Members.Any(m => m.UserId == userId));
            if (list is null) return Results.NotFound();
            var maxPosition = await db.ShoppingItems.Where(x => x.ShoppingListId == listId).MaxAsync(x => (int?)x.Position) ?? -1;
            var item = new ShoppingItem
            {
                ShoppingListId = listId,
                Name = request.Name.Trim(),
                Quantity = request.Quantity <= 0 ? 1 : request.Quantity,
                Unit = string.IsNullOrWhiteSpace(request.Unit) ? "db" : request.Unit.Trim(),
                Note = string.IsNullOrWhiteSpace(request.Note) ? null : request.Note.Trim(),
                AddedByUserId = userId,
                Position = maxPosition + 1
            };
            list.UpdatedAt = DateTimeOffset.UtcNow;
            db.ShoppingItems.Add(item);
            await db.SaveChangesAsync();
            await NotifyListChanged(hub, listId);
            return Results.Created($"/api/items/{item.Id}", ToDto(item));
        });

        items.MapPatch("/{itemId:guid}", async (
            Guid itemId,
            UpdateItemRequest request,
            System.Security.Claims.ClaimsPrincipal principal,
            AppDbContext db,
            IHubContext<ShoppingHub> hub) =>
        {
            var userId = principal.UserId();
            var item = await db.ShoppingItems
                .Include(x => x.ShoppingList)
                .FirstOrDefaultAsync(x => x.Id == itemId && x.ShoppingList.Household.Members.Any(m => m.UserId == userId));
            if (item is null) return Results.NotFound();
            if (request.Name is not null)
            {
                if (string.IsNullOrWhiteSpace(request.Name)) return Results.BadRequest(new { message = "Item name cannot be empty." });
                item.Name = request.Name.Trim();
            }
            if (request.Quantity is > 0) item.Quantity = request.Quantity.Value;
            if (request.Unit is not null) item.Unit = string.IsNullOrWhiteSpace(request.Unit) ? "db" : request.Unit.Trim();
            if (request.Note is not null) item.Note = string.IsNullOrWhiteSpace(request.Note) ? null : request.Note.Trim();
            if (request.Position is not null) item.Position = request.Position.Value;
            if (request.IsChecked is not null)
            {
                item.IsChecked = request.IsChecked.Value;
                item.CheckedByUserId = item.IsChecked ? userId : null;
            }
            item.UpdatedAt = DateTimeOffset.UtcNow;
            item.ShoppingList.UpdatedAt = item.UpdatedAt;
            await db.SaveChangesAsync();
            await NotifyListChanged(hub, item.ShoppingListId);
            return Results.Ok(ToDto(item));
        });

        items.MapDelete("/{itemId:guid}", async (
            Guid itemId,
            System.Security.Claims.ClaimsPrincipal principal,
            AppDbContext db,
            IHubContext<ShoppingHub> hub) =>
        {
            var userId = principal.UserId();
            var item = await db.ShoppingItems
                .Include(x => x.ShoppingList)
                .FirstOrDefaultAsync(x => x.Id == itemId && x.ShoppingList.Household.Members.Any(m => m.UserId == userId));
            if (item is null) return Results.NotFound();
            var listId = item.ShoppingListId;
            item.ShoppingList.UpdatedAt = DateTimeOffset.UtcNow;
            db.ShoppingItems.Remove(item);
            await db.SaveChangesAsync();
            await NotifyListChanged(hub, listId);
            return Results.NoContent();
        });
    }

    private static void MapProducts(WebApplication app)
    {
        var api = app.MapGroup("/api/products").RequireAuthorization();
        api.MapGet("/search", async (
            string q,
            string? stores,
            PriceLookupService prices,
            CancellationToken cancellationToken) =>
        {
            var result = await prices.SearchAsync(q, stores, cancellationToken);
            return Results.Ok(result);
        });
    }

    private static async Task<bool> IsHouseholdMember(AppDbContext db, Guid householdId, Guid userId) =>
        await db.HouseholdMembers.AnyAsync(x => x.HouseholdId == householdId && x.UserId == userId);

    private static ShoppingListDto ToDto(ShoppingList list) => new(
        list.Id,
        list.HouseholdId,
        list.Name,
        list.Items.OrderBy(x => x.IsChecked).ThenBy(x => x.Position).ThenBy(x => x.CreatedAt).Select(ToDto).ToList(),
        list.UpdatedAt);

    private static ShoppingItemDto ToDto(ShoppingItem item) => new(
        item.Id, item.Name, item.Quantity, item.Unit, item.Note, item.IsChecked, item.Position,
        item.AddedByUserId, item.CheckedByUserId, item.UpdatedAt);

    private static Task NotifyListChanged(IHubContext<ShoppingHub> hub, Guid listId) =>
        hub.Clients.Group(ShoppingHub.GroupName(listId)).SendAsync(ShoppingHubEvents.ListChanged, listId.ToString());
}
