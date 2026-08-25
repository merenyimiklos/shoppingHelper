package hu.merenyi.shoppinghelper

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ShoppingHelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun ShoppingHelperApp(vm: AppViewModel) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.restoringSession -> LoadingScreen()
                !state.authenticated -> AuthScreen(
                    loading = state.loading,
                    onLogin = vm::login,
                    onRegister = vm::register
                )
                state.selectedHousehold == null -> HouseholdPickerScreen(
                    state = state,
                    onSelect = vm::selectHousehold,
                    onCreate = vm::createHousehold,
                    onJoin = vm::joinHousehold,
                    onLogout = vm::logout
                )
                state.selectedList == null -> HouseholdListsScreen(
                    state = state,
                    onBack = vm::leaveHouseholdView,
                    onCreateList = vm::createList,
                    onSelectList = { vm.selectList(it.id) },
                    onCreateInvite = vm::createInvite
                )
                else -> ShoppingListScreen(
                    state = state,
                    onBack = vm::leaveListView,
                    onAdd = { vm.addItem(it) },
                    onToggle = vm::toggleItem,
                    onDelete = vm::deleteItem,
                    onPrices = { vm.searchOffers(it.name) },
                    onDismissOffers = vm::dismissOffers,
                    onCompareBasket = vm::compareBasket,
                    onDismissBasketComparison = vm::dismissBasketComparison,
                    onRefresh = vm::refreshSelectedList
                )
            }

            if (state.loading && !state.restoringSession) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AuthScreen(
    loading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit
) {
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("ShoppingHelper", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Közös bevásárlólista és bolti ár-összehasonlítás", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            if (registerMode) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Név") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Jelszó") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !loading && email.isNotBlank() && password.isNotBlank() && (!registerMode || displayName.isNotBlank()),
                onClick = {
                    if (registerMode) onRegister(email, password, displayName) else onLogin(email, password)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (registerMode) "Regisztráció" else "Belépés")
            }
            TextButton(onClick = { registerMode = !registerMode }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (registerMode) "Már van fiókom" else "Még nincs fiókom")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HouseholdPickerScreen(
    state: AppUiState,
    onSelect: (HouseholdDto) -> Unit,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onLogout: () -> Unit
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Háztartások") },
            actions = { IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Kijelentkezés") } }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Szia, ${state.displayName}!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Válassz közös háztartást, vagy hozz létre egyet.")
            }
            items(state.households, key = { it.id }) { household ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(household) },
                    colors = CardDefaults.cardColors()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(household.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${household.memberCount} tag • ${if (household.role == "owner") "Tulajdonos" else "Tag"}")
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("Új háztartás", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Név") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName); newName = "" }) {
                        Icon(Icons.Default.Add, "Létrehozás")
                    }
                }
            }
            item {
                Text("Csatlakozás meghívókóddal", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it.uppercase() },
                        label = { Text("Meghívókód") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(enabled = inviteCode.isNotBlank(), onClick = { onJoin(inviteCode); inviteCode = "" }) {
                        Text("Belépek")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HouseholdListsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onCreateList: (String) -> Unit,
    onSelectList: (ShoppingListSummaryDto) -> Unit,
    onCreateInvite: () -> Unit
) {
    var listName by rememberSaveable { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }

    LaunchedEffect(state.invite) {
        if (state.invite != null) showInvite = true
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.selectedHousehold?.name.orEmpty()) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Vissza") } },
            actions = { IconButton(onClick = onCreateInvite) { Icon(Icons.Default.GroupAdd, "Partner meghívása") } }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Bevásárlólisták", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            items(state.lists, key = { it.id }) { list ->
                Card(Modifier.fillMaxWidth().clickable { onSelectList(list) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${list.openItems} hátra • ${list.totalItems} összesen")
                        }
                        Text("→", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = listName,
                        onValueChange = { listName = it },
                        label = { Text("Új lista") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(enabled = listName.isNotBlank(), onClick = { onCreateList(listName); listName = "" }) {
                        Icon(Icons.Default.Add, "Lista létrehozása")
                    }
                }
            }
        }
    }

    if (showInvite && state.invite != null) {
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Partner meghívása") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A párod a Csatlakozás meghívókóddal résznél írja be ezt:")
                    SelectionContainer {
                        Text(state.invite.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("A kód 7 napig és egy csatlakozásra érvényes.")
                }
            },
            confirmButton = { TextButton(onClick = { showInvite = false }) { Text("Rendben") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingListScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onToggle: (ShoppingItemDto) -> Unit,
    onDelete: (ShoppingItemDto) -> Unit,
    onPrices: (ShoppingItemDto) -> Unit,
    onDismissOffers: () -> Unit,
    onCompareBasket: () -> Unit,
    onDismissBasketComparison: () -> Unit,
    onRefresh: () -> Unit
) {
    var newItem by rememberSaveable { mutableStateOf("") }
    val list = state.selectedList ?: return
    val openItems = list.items.count { !it.isChecked }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) onAdd(text)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(list.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Vissza") } },
            actions = {
                IconButton(enabled = openItems > 0, onClick = onCompareBasket) {
                    Icon(Icons.Default.ShoppingCart, "Kosár ár-összehasonlítás")
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Frissítés") }
            }
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text("Mit vegyünk?") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Mondd a termék nevét")
                }
                speechLauncher.launch(intent)
            }) { Icon(Icons.Default.Mic, "Bemondás") }
            IconButton(enabled = newItem.isNotBlank(), onClick = { onAdd(newItem); newItem = "" }) {
                Icon(Icons.Default.Add, "Hozzáadás")
            }
        }

        if (openItems > 0) {
            FilledTonalButton(
                onClick = onCompareBasket,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.ShoppingCart, null)
                Spacer(Modifier.size(8.dp))
                Text("Hol olcsóbb a kosár? • Lidl vs SPAR")
            }
            Spacer(Modifier.height(6.dp))
        }

        if (list.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("A lista még üres. Adj hozzá valamit vagy mondd be mikrofonnal.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list.items, key = { it.id }) { item ->
                    ShoppingItemRow(item, onToggle, onDelete, onPrices)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (state.offerQuery != null) {
        ModalBottomSheet(onDismissRequest = onDismissOffers) {
            OfferSheet(state.offerQuery, state.offers)
        }
    }

    state.basketComparison?.let { comparison ->
        ModalBottomSheet(onDismissRequest = onDismissBasketComparison) {
            BasketComparisonSheet(comparison)
        }
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItemDto,
    onToggle: (ShoppingItemDto) -> Unit,
    onDelete: (ShoppingItemDto) -> Unit,
    onPrices: (ShoppingItemDto) -> Unit
) {
    Card(Modifier.fillMaxWidth().alpha(if (item.isChecked) 0.65f else 1f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = item.isChecked, onCheckedChange = { onToggle(item) })
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None
                )
                val qty = if (item.quantity == 1.0 && item.unit == "db") null else "${prettyQuantity(item.quantity)} ${item.unit}"
                if (qty != null || !item.note.isNullOrBlank()) {
                    Text(listOfNotNull(qty, item.note).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { onPrices(item) }) { Icon(Icons.Default.PriceCheck, "Árak és termékek") }
            IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, "Törlés") }
        }
    }
}

@Composable
private fun OfferSheet(query: String, offers: List<ProductOfferDto>) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Árak: $query", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Árfigyelő és importált napi adatok • Lidl + SPAR", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (offers.isEmpty()) {
            Text("Ehhez a kereséshez most nincs Lidl/SPAR találat.")
            Spacer(Modifier.height(28.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(offers, key = { it.id }) { offer ->
                    val clickable = if (!offer.productUrl.isNullOrBlank()) {
                        Modifier.clickable { uriHandler.openUri(offer.productUrl) }
                    } else Modifier
                    Card(Modifier.fillMaxWidth().then(clickable)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProductImage(offer.imageUrl, offer.productName)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(offer.store, fontWeight = FontWeight.Bold)
                                    if (!offer.productUrl.isNullOrBlank()) {
                                        Spacer(Modifier.size(4.dp))
                                        Icon(Icons.Default.OpenInNew, "Termék megnyitása", modifier = Modifier.size(15.dp))
                                    }
                                }
                                Text(offer.productName, style = MaterialTheme.typography.bodyMedium)
                                if (!offer.brand.isNullOrBlank()) Text(offer.brand, style = MaterialTheme.typography.bodySmall)
                                if (!offer.packageSize.isNullOrBlank()) Text(offer.packageSize, style = MaterialTheme.typography.bodySmall)
                                offer.unitPrice?.let {
                                    Text(
                                        "Egységár: ${formatHuf(it)}${offer.unitPriceUnit?.let { u -> "/$u" }.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text("Áradat: ${offer.priceDate}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(formatHuf(offer.price), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun BasketComparisonSheet(comparison: BasketComparisonDto) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Hol olcsóbb a kosár?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Becsült összeg a nyitott tételekre. A hiányzó találatok nincsenek beleszámolva.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))

        if (comparison.stores.isEmpty()) {
            Text("Most nem sikerült összehasonlítható árakat találni.")
            Spacer(Modifier.height(28.dp))
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(comparison.stores, key = { it.store }) { store ->
                val isBest = store == comparison.stores.first()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    if (isBest) "${store.store} • legjobb" else store.store,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${store.matchedItems}/${store.matchedItems + store.missingItems} tétel árazva" +
                                        if (store.missingItems > 0) " • ${store.missingItems} nincs találat" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Becsült", style = MaterialTheme.typography.labelSmall)
                                Text(formatHuf(store.estimatedTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider()

                        store.lines.forEach { line ->
                            val lineClickable = if (!line.productUrl.isNullOrBlank()) {
                                Modifier.clickable { uriHandler.openUri(line.productUrl) }
                            } else Modifier
                            Row(
                                Modifier.fillMaxWidth().then(lineClickable).padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (line.matched) {
                                    ProductImage(line.imageUrl, line.productName ?: line.query, size = 48)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${prettyQuantity(line.quantity)} ${line.unit} ${line.query}",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (line.matched) {
                                        Text(line.productName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                        val details = listOfNotNull(
                                            line.packageSize,
                                            line.packagePrice?.let { "csomag ${formatHuf(it)}" }
                                        ).joinToString(" • ")
                                        if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Text("Nincs megfelelő találat", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                line.estimatedTotal?.let {
                                    Text(formatHuf(it), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Az árak tájékoztató jellegűek. Az Árfigyelő nem valós idejű bolti készletinformáció.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun ProductImage(imageUrl: String?, contentDescription: String, size: Int = 72) {
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PriceCheck, null, modifier = Modifier.size((size / 2).dp))
        }
    }
}

private fun prettyQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
private fun formatHuf(value: Double): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("hu-HU")).format(value)
