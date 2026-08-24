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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.OutlinedButton
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
    onRefresh: () -> Unit
) {
    var newItem by rememberSaveable { mutableStateOf("") }
    val list = state.selectedList ?: return
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) onAdd(text)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(list.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Vissza") } },
            actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Frissítés") } }
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
            IconButton(onClick = { onPrices(item) }) { Icon(Icons.Default.PriceCheck, "Árak") }
            IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, "Törlés") }
        }
    }
}

@Composable
private fun OfferSheet(query: String, offers: List<ProductOfferDto>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Árak: $query", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A legfrissebb importált Lidl/SPAR ajánlatok", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (offers.isEmpty()) {
            Text("Ehhez a kereséshez nincs aktuális találat az adatbázisban.")
            Spacer(Modifier.height(28.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(offers, key = { it.id }) { offer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!offer.imageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = offer.imageUrl,
                                    contentDescription = offer.productName,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(Modifier.size(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(offer.store, fontWeight = FontWeight.Bold)
                                Text(offer.productName, style = MaterialTheme.typography.bodyMedium)
                                if (!offer.packageSize.isNullOrBlank()) Text(offer.packageSize, style = MaterialTheme.typography.bodySmall)
                                offer.unitPrice?.let {
                                    Text("Egységár: ${formatHuf(it)}${offer.unitPriceUnit?.let { u -> "/$u" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                }
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

private fun prettyQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
private fun formatHuf(value: Double): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("hu-HU")).format(value)
