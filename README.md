# ShoppingHelper

Közös, valós időben szinkronizált bevásárlólista Androidra, saját hosztolású ASP.NET Core backenddel és bolti ár-összehasonlításra előkészített termékadatbázissal.

## Funkciók

- Kotlin + Jetpack Compose Android kliens
- regisztráció és JWT alapú bejelentkezés
- közös háztartások
- páros/családi meghívás egyszer használatos meghívókóddal
- több közös bevásárlólista
- termék hozzáadás, kipipálás és törlés
- SignalR alapú valós idejű frissítés két vagy több készülék között
- magyar nyelvű hangbevitel Android `RecognizerIntent` segítségével
- Lidl/SPAR árkereső felület
- termékkép megjelenítése, ha az importált adatforrás képlinket tartalmaz
- napi CSV / JSON / ZIP termékadat-import a backendbe
- PostgreSQL
- Docker Compose alapú self-hosted telepítés
- GitHub Actions CI backend- és Android-builddel

## Felépítés

```text
Android / Jetpack Compose
        |
        | HTTPS REST + SignalR/WebSocket
        v
ASP.NET Core 10 API
        |
        +---- PostgreSQL
        |
        +---- napi termék-/áradat import
```

A közös listákhoz az Android kliens a REST API-t használja, a változásokról pedig SignalR eseményt kap. Így ha az egyik felhasználó hozzáad vagy kipipál egy terméket, a másik kliens automatikusan újratölti az aktuális listát.

## Repository

```text
android/                         Android / Compose kliens
backend/ShoppingHelper.Api/     ASP.NET Core backend
.github/workflows/ci.yml        CI
docker-compose.yml              szerveres stack
.env.example                    környezeti változók mintája
```

# Backend futtatása Dockerrel

A szerveren:

```bash
git clone https://github.com/merenyimiklos/shoppingHelper.git
cd shoppingHelper
cp .env.example .env
```

Generálj külön adatbázisjelszót és JWT kulcsot, majd írd be a `.env` fájlba. Például:

```bash
openssl rand -base64 32
openssl rand -base64 48
```

Ezután:

```bash
docker compose up -d --build
```

Állapot:

```bash
docker compose ps
curl http://127.0.0.1:5180/health
```

Naplók:

```bash
docker compose logs -f api
docker compose logs -f db
```

Frissítés:

```bash
git pull
docker compose up -d --build
```

## Jellyfin mellett

A ShoppingHelper stack nem használ `container_name`-et, saját Docker networkön fut, és a PostgreSQL portját nem publikálja a host felé. Az API alapértelmezésben csak ezt a host portot használja:

```text
127.0.0.1:5180 -> ShoppingHelper API :8080
```

Ezért egy külön Jellyfin Docker Compose projekttől függetlenül futtatható, amíg a `5180` host port szabad. Ha foglalt, a `.env` fájlban változtasd meg:

```dotenv
SHOPPINGHELPER_PORT=5181
```

## HTTPS / reverse proxy

Telefonról éles használathoz az API-t HTTPS-en érdemes elérhetővé tenni. Az API alapból localhostra van kötve, ezért a már meglévő Caddy/Nginx/Traefik proxy továbbíthatja a publikus domaint a következő címre:

```text
http://127.0.0.1:5180
```

SignalR/WebSocket miatt a reverse proxyban a WebSocket kapcsolatot is engedni kell.

Példa Caddy konfiguráció:

```caddyfile
shopping.example.hu {
    reverse_proxy 127.0.0.1:5180
}
```

Példa Nginx location:

```nginx
location / {
    proxy_pass http://127.0.0.1:5180;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

# Környezeti változók

`.env`:

```dotenv
POSTGRES_DB=shoppinghelper
POSTGRES_USER=shoppinghelper
POSTGRES_PASSWORD=EROS_EGYEDI_JELSZO
JWT_KEY=LEGALABB_32_KARAKTERES_VELETLEN_TITOK
SHOPPINGHELPER_BIND=127.0.0.1
SHOPPINGHELPER_PORT=5180
GVH_DAILY_DATA_URL=
```

A `.env` nincs verziókezelve.

# Termék- és áradatok

A backend `ProductImportWorker` szolgáltatása naponta egyszer képes CSV, JSON vagy ZIP-ben érkező adatot importálni. A forrás URL-je nincs a kódba égetve:

```dotenv
GVH_DAILY_DATA_URL=https://...
```

Az importer több magyar és angol oszlopnevet felismer, normalizálja a boltlánc nevét, elmenti az aktuális árat, egységárat, kiszerelést, márkát, EAN/azonosítót, terméklinket és képlinket, ha ezek megtalálhatók a forrásban.

**Fontos:** amíg a `GVH_DAILY_DATA_URL` nincs egy stabil és felhasználható napi adatforrásra állítva, az árkereső felülete működik, de az adatbázisban nem lesznek élő bolti árak. A rendszer szándékosan nem függ törékeny HTML scrapertől.

A jelenlegi Android árkeresés alapértelmezésben Lidl és SPAR ajánlatokat kér le.

A bolti készlet (`van-e most 3 darab a polcon`) külön adatforrást igényel; a jelenlegi rendszer termék-/áradatot kezel, valós idejű üzletkészletet nem állít elő.

# Android

## Android Studio

Nyisd meg az `android` könyvtárat Android Studioban.

A fejlesztői alap API URL emulatorból:

```text
http://10.0.2.2:5180/
```

Valódi telefonhoz add meg a publikus HTTPS API URL-t Gradle propertyként, például a saját `~/.gradle/gradle.properties` fájlban:

```properties
SHOPPINGHELPER_API_URL=https://shopping.example.hu/
```

Ne commitolj privát domaint vagy kulcsot a repóba.

## Build parancssorból

A CI Gradle 9.4.1-gyel fordítja a projektet:

```bash
gradle -p android :app:assembleDebug
```

Az APK az `android/app/build/outputs/apk/debug/` alatt jön létre.

# API röviden

## Auth

```text
POST /api/auth/register
POST /api/auth/login
```

## Háztartások

```text
GET  /api/households/
POST /api/households/
POST /api/households/join
POST /api/households/{householdId}/invite
GET  /api/households/{householdId}/lists
POST /api/households/{householdId}/lists
```

## Bevásárlólista

```text
GET    /api/lists/{listId}
POST   /api/lists/{listId}/items
PATCH  /api/items/{itemId}
DELETE /api/items/{itemId}
```

## Árkeresés

```text
GET /api/products/search?q=cottage%20cheese&stores=Lidl,SPAR
```

## Realtime

```text
/hubs/shopping
```

A kliens `JoinList` hub metódussal lép be a lista csoportjába, a backend pedig `ListChanged` eseményt küld módosítás után.

# Biztonság

- a PostgreSQL nincs kitéve publikus host portra
- az API alapértelmezésben csak `127.0.0.1` interfészre van bindolva
- JWT kulcs és DB jelszó `.env`-ből jön
- a `.env` Gitből ignorálva van
- éles telefonos használathoz HTTPS szükséges
- az alap `appsettings.json` fejlesztői értékeket tartalmaz; productionben a Docker Compose environment felülírja őket

# CI

A GitHub Actions minden `main` pushnál és pull requestnél:

1. restore + buildeli a .NET backendet,
2. telepíti az Android SDK 37-et,
3. elkészíti a debug Android APK-t.

# Következő fejlesztések

- offline-first Room cache és konfliktuskezelés
- mennyiség/egység szerkesztő UI
- listaelem átrendezés
- gyakran vásárolt termékek
- bolt szerinti automatikus csoportosítás
- teljes kosár árának összehasonlítása
- több áruházlánc
- termékkép-kiegészítő provider, ha a napi adatforrás nem ad képet
- push értesítés, ha a partner új elemet ad hozzá
