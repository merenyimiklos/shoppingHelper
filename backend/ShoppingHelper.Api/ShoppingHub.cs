using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace ShoppingHelper.Api;

[Authorize]
public sealed class ShoppingHub(AppDbContext db) : Hub
{
    public async Task JoinList(string listId)
    {
        if (!Guid.TryParse(listId, out var parsed))
            throw new HubException("Invalid list id.");

        var userId = Context.User?.UserId() ?? throw new HubException("Unauthorized.");
        var allowed = await db.ShoppingLists
            .Where(x => x.Id == parsed)
            .AnyAsync(x => x.Household.Members.Any(m => m.UserId == userId));

        if (!allowed)
            throw new HubException("You are not a member of this household.");

        await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(parsed));
    }

    public Task LeaveList(string listId)
    {
        if (!Guid.TryParse(listId, out var parsed)) return Task.CompletedTask;
        return Groups.RemoveFromGroupAsync(Context.ConnectionId, GroupName(parsed));
    }

    public static string GroupName(Guid listId) => $"list:{listId:N}";
}

public static class ShoppingHubEvents
{
    public const string ListChanged = "ListChanged";
}
