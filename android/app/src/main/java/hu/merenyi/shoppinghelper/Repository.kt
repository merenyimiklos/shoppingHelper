package hu.merenyi.shoppinghelper

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.sessionDataStore by preferencesDataStore(name = "shoppinghelper_session")

class SessionStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("token")
    private val displayNameKey = stringPreferencesKey("display_name")
    private val emailKey = stringPreferencesKey("email")

    suspend fun load(): SavedSession? {
        val prefs = context.sessionDataStore.data.first()
        val token = prefs[tokenKey] ?: return null
        return SavedSession(token, prefs[displayNameKey].orEmpty(), prefs[emailKey].orEmpty())
    }

    suspend fun save(auth: AuthResponse) {
        context.sessionDataStore.edit {
            it[tokenKey] = auth.token
            it[displayNameKey] = auth.displayName
            it[emailKey] = auth.email
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}

data class SavedSession(val token: String, val displayName: String, val email: String)

class ShoppingRepository(context: Context) {
    val sessionStore = SessionStore(context)
    @Volatile private var token: String? = null
    private val apiClient = ApiClient { token }
    private val api get() = apiClient.api
    private var hub: HubConnection? = null

    fun setToken(value: String?) {
        token = value
    }

    suspend fun register(email: String, password: String, displayName: String) =
        api.register(RegisterRequest(email, password, displayName))

    suspend fun login(email: String, password: String) = api.login(LoginRequest(email, password))
    suspend fun households() = api.households()
    suspend fun createHousehold(name: String) = api.createHousehold(CreateHouseholdRequest(name))
    suspend fun joinHousehold(code: String) = api.joinHousehold(JoinHouseholdRequest(code))
    suspend fun createInvite(householdId: String) = api.createInvite(householdId)
    suspend fun lists(householdId: String) = api.lists(householdId)
    suspend fun createList(householdId: String, name: String) = api.createList(householdId, CreateListRequest(name))
    suspend fun list(listId: String) = api.list(listId)
    suspend fun addItem(listId: String, name: String, quantity: Double = 1.0, unit: String = "db", note: String? = null) =
        api.addItem(listId, CreateItemRequest(name, quantity, unit, note))
    suspend fun updateItem(itemId: String, update: UpdateItemRequest) = api.updateItem(itemId, update)
    suspend fun deleteItem(itemId: String) = api.deleteItem(itemId)
    suspend fun searchProducts(query: String, stores: String? = "Lidl,SPAR") = api.searchProducts(query, stores)

    suspend fun connectRealtime(listId: String, onListChanged: (String) -> Unit) = withContext(Dispatchers.IO) {
        disconnectRealtime()
        val currentToken = token ?: return@withContext
        val hubUrl = BuildConfig.API_BASE_URL.trimEnd('/') + "/hubs/shopping?access_token=" + Uri.encode(currentToken)
        val connection = HubConnectionBuilder.create(hubUrl).build()
        connection.on("ListChanged", { changedId: String -> onListChanged(changedId) }, String::class.java)
        connection.start().blockingAwait()
        connection.send("JoinList", listId)
        hub = connection
    }

    suspend fun disconnectRealtime() = withContext(Dispatchers.IO) {
        runCatching { hub?.stop()?.blockingAwait() }
        hub = null
    }
}
