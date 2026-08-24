using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace ShoppingHelper.Api;

public interface IJwtTokenService
{
    string Create(AppUser user);
}

public sealed class JwtTokenService(IConfiguration configuration) : IJwtTokenService
{
    public string Create(AppUser user)
    {
        var key = configuration["Jwt:Key"] ?? throw new InvalidOperationException("Jwt:Key is not configured.");
        if (key.Length < 32)
            throw new InvalidOperationException("Jwt:Key must be at least 32 characters long.");

        var issuer = configuration["Jwt:Issuer"] ?? "ShoppingHelper";
        var audience = configuration["Jwt:Audience"] ?? "ShoppingHelper.Android";
        var claims = new[]
        {
            new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString()),
            new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
            new Claim(ClaimTypes.Name, user.DisplayName),
            new Claim(ClaimTypes.Email, user.Email ?? string.Empty),
            new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString())
        };

        var credentials = new SigningCredentials(
            new SymmetricSecurityKey(Encoding.UTF8.GetBytes(key)),
            SecurityAlgorithms.HmacSha256);

        var token = new JwtSecurityToken(
            issuer,
            audience,
            claims,
            expires: DateTime.UtcNow.AddDays(30),
            signingCredentials: credentials);

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}

public static class ClaimsPrincipalExtensions
{
    public static Guid UserId(this ClaimsPrincipal principal)
    {
        var raw = principal.FindFirstValue(ClaimTypes.NameIdentifier)
                  ?? principal.FindFirstValue(JwtRegisteredClaimNames.Sub)
                  ?? throw new UnauthorizedAccessException("Missing user id claim.");
        return Guid.Parse(raw);
    }
}

public static class TextNormalizer
{
    public static string NormalizeSearch(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        var decomposed = value.Trim().ToLowerInvariant().Normalize(System.Text.NormalizationForm.FormD);
        var chars = decomposed
            .Where(c => System.Globalization.CharUnicodeInfo.GetUnicodeCategory(c) != System.Globalization.UnicodeCategory.NonSpacingMark)
            .ToArray();
        return new string(chars).Normalize(System.Text.NormalizationForm.FormC);
    }
}
