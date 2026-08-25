package hu.merenyi.shoppinghelper

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import retrofit2.HttpException


data class AppUiState(
    val restoringSession: Boolean = true,
    val authenticated: Boolean = false,
    val displayName: String = "",
    val email: String = "",
    val households: List<HouseholdDto> = emptyList(),
    val selectedHousehold: HouseholdDto? = null,
    val lists: List<ShoppingListSummaryDto> = emptyList(),
    val selectedList: ShoppingListDto? = null,
    val offers: List<ProductOfferDto> = emptyList(),
    val offerQuery: String? = null,
    val basketComparison: BasketComparisonDto? = null,
    val invite: InviteDto? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ShoppingRepository(application)
    var state by mutableStateOf(AppUiState())
        private set

    init {
        restoreSession()
    }

    private fun restoreSession() = viewModelScope.launch {
        val saved = repository.sessionStore.load()
        if (saved == null) {
            state = state.copy(restoringSession = false)
            return@launch
        }
        repository.setToken(saved.token)
        state = state.copy(
            restoringSession = false,
            authenticated = true,
            displayName = saved.displayName,
            email = saved.email
        )
        loadHouseholds()
    }

    fun login(email: String, password: String) = runAction {
        val auth = repository.login(email, password)
        onAuthenticated(auth)
    }

    fun register(email: String, password: String, displayName: String) = runAction {
        val auth = repository.register(email, password, displayName)
        onAuthenticated(auth)
    }

    private suspend fun onAuthenticated(auth: AuthResponse) {
        repository.setToken(auth.token)
        repository.sessionStore.save(auth)
        state = state.copy(authenticated = true, displayName = auth.displayName, email = auth.email)
        loadHouseholdsInternal()
    }

    fun logout() = viewModelScope.launch {
        repository.disconnectRealtime()
        repository.setToken(null)
        repository.sessionStore.clear()
        state = AppUiState(restoringSession = false)
    }

    fun loadHouseholds() = runAction { loadHouseholdsInternal() }

    private suspend fun loadHouseholdsInternal() {
        val households = repository.households()
        state = state.copy(households = households)
        if (households.size == 1 && state.selectedHousehold == null) {
            selectHouseholdInternal(households.first())
        }
    }

    fun createHousehold(name: String) = runAction {
        val household = repository.createHousehold(name)
        state = state.copy(households = (state.households + household).distinctBy { it.id })
        selectHouseholdInternal(household)
    }

    fun joinHousehold(code: String) = runAction {
        val household = repository.joinHousehold(code)
        state = state.copy(households = (state.households + household).distinctBy { it.id })
        selectHouseholdInternal(household)
    }

    fun selectHousehold(household: HouseholdDto) = runAction {
        selectHouseholdInternal(household)
    }

    private suspend fun selectHouseholdInternal(household: HouseholdDto) {
        repository.disconnectRealtime()
        val lists = repository.lists(household.id)
        state = state.copy(
            selectedHousehold = household,
            lists = lists,
            selectedList = null,
            invite = null,
            basketComparison = null
        )
        if (lists.size == 1) selectListInternal(lists.first().id)
    }

    fun leaveHouseholdView() = viewModelScope.launch {
        repository.disconnectRealtime()
        state = state.copy(
            selectedHousehold = null,
            lists = emptyList(),
            selectedList = null,
            invite = null,
            basketComparison = null
        )
    }

    fun createInvite() {
        val household = state.selectedHousehold ?: return
        runAction {
            state = state.copy(invite = repository.createInvite(household.id))
        }
    }

    fun createList(name: String) {
        val household = state.selectedHousehold ?: return
        runAction {
            val created = repository.createList(household.id, name)
            state = state.copy(lists = state.lists + created)
            selectListInternal(created.id)
        }
    }

    fun selectList(listId: String) = runAction { selectListInternal(listId) }

    private suspend fun selectListInternal(listId: String) {
        val list = repository.list(listId)
        state = state.copy(
            selectedList = list,
            offers = emptyList(),
            offerQuery = null,
            basketComparison = null
        )
        runCatching {
            repository.connectRealtime(list.id) { changedId ->
                if (changedId.equals(state.selectedList?.id, ignoreCase = true)) {
                    viewModelScope.launch { refreshSelectedListInternal() }
                }
            }
        }
    }

    fun leaveListView() = viewModelScope.launch {
        repository.disconnectRealtime()
        state = state.copy(
            selectedList = null,
            offers = emptyList(),
            offerQuery = null,
            basketComparison = null
        )
    }

    fun addItem(name: String, quantity: Double = 1.0, unit: String = "db", note: String? = null) {
        val listId = state.selectedList?.id ?: return
        if (name.isBlank()) return
        runAction {
            repository.addItem(listId, name.trim(), quantity, unit, note)
            refreshSelectedListInternal()
            state = state.copy(basketComparison = null)
        }
    }

    fun toggleItem(item: ShoppingItemDto) = runAction {
        repository.updateItem(item.id, UpdateItemRequest(isChecked = !item.isChecked))
        refreshSelectedListInternal()
        state = state.copy(basketComparison = null)
    }

    fun deleteItem(item: ShoppingItemDto) = runAction {
        repository.deleteItem(item.id)
        refreshSelectedListInternal()
        state = state.copy(basketComparison = null)
    }

    fun searchOffers(itemName: String) = runAction {
        val offers = repository.searchProducts(itemName)
        state = state.copy(offers = offers, offerQuery = itemName)
    }

    fun dismissOffers() {
        state = state.copy(offers = emptyList(), offerQuery = null)
    }

    fun compareBasket() {
        val listId = state.selectedList?.id ?: return
        runAction {
            state = state.copy(basketComparison = repository.priceComparison(listId))
        }
    }

    fun dismissBasketComparison() {
        state = state.copy(basketComparison = null)
    }

    fun refreshSelectedList() = runAction { refreshSelectedListInternal() }

    private suspend fun refreshSelectedListInternal() {
        val listId = state.selectedList?.id ?: return
        val list = repository.list(listId)
        state = state.copy(selectedList = list)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    private fun runAction(block: suspend () -> Unit) = viewModelScope.launch {
        state = state.copy(loading = true, error = null)
        try {
            block()
        } catch (t: Throwable) {
            state = state.copy(error = userMessage(t))
        } finally {
            state = state.copy(loading = false)
        }
    }

    private fun userMessage(t: Throwable): String = when (t) {
        is HttpException -> when (t.code()) {
            401 -> "Hibás e-mail cím vagy jelszó, vagy lejárt a munkamenet."
            403 -> "Ehhez nincs jogosultságod."
            404 -> "A kért elem már nem található."
            409 -> "Ez az e-mail cím már regisztrálva van."
            else -> "Szerverhiba (${t.code()})."
        }
        else -> t.message ?: "Ismeretlen hiba történt."
    }
}
