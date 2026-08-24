FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY ShoppingHelper.Api.csproj ./
RUN dotnet restore ShoppingHelper.Api.csproj
COPY . ./
RUN dotnet publish ShoppingHelper.Api.csproj -c Release -o /app/publish --no-restore /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
ENV ASPNETCORE_URLS=http://+:8080 \
    DOTNET_EnableDiagnostics=0
EXPOSE 8080
COPY --from=build /app/publish ./
USER $APP_UID
ENTRYPOINT ["dotnet", "ShoppingHelper.Api.dll"]
