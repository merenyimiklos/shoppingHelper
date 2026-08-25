package hu.merenyi.shoppinghelper

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// Requests / responses mirror the ASP.NET Core API contracts.
data class RegisterRequest(val email: String, val password: String, val displayName: String)
data class LoginRequest(val email: String, val password: String)
data class AuthResponse(val token: String, val userId: String, val displayName: String, val email: String)
data class CreateHouseholdRequest(val name: String)
data class JoinHouseholdRequest(val code: String)
data class CreateListRequest(val name: String)
data class CreateItemRequest(val name: String, val quantity: Double = 1.0, val unit: String = "db", val note: String? = null)
data class UpdateItemRequest(
    val name: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val note: String? = null,
    val isChecked: Boolean? = null,
    val position: Int? = null
)

data class HouseholdDto(val id: String, val name: String, val role: String, val ownerId: String, val memberCount: Int)
data class InviteDto(val code: String, val expiresAt: String)
data class ShoppingListSummaryDto(val id: String, val name: String, val openItems: Int, val totalItems: Int, val updatedAt: String)
data class ShoppingItemDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val note: String?,
    val isChecked: Boolean,
    val position: Int,
    val addedByUserId: String,
    val checkedByUserId: String?,
    val updatedAt: String
)
data class ShoppingListDto(val id: String, val householdId: String, val name: String, val items: List<ShoppingItemDto>, val updatedAt: String)
data class ProductOfferDto(
    val id: String,
    val store: String,
    val productName: String,
    val brand: String?,
    val packageSize: String?,
    val price: Double,
    val unitPrice: Double?,
    val unitPriceUnit: String?,
    val imageUrl: String?,
    val productUrl: String?,
    val priceDate: String
)

data class BasketLineDto(
    val itemId: String,
    val query: String,
    val quantity: Double,
    val unit: String,
    val store: String,
    val matched: Boolean,
    val productName: String?,
    val packageSize: String?,
    val packagePrice: Double?,
    val unitPrice: Double?,
    val unitPriceUnit: String?,
    val estimatedTotal: Double?,
    val imageUrl: String?,
    val productUrl: String?,
    val priceDate: String?
)

data class StoreBasketDto(
    val store: String,
    val estimatedTotal: Double,
    val matchedItems: Int,
    val missingItems: Int,
    val lines: List<BasketLineDto>
)

data class BasketComparisonDto(
    val listId: String,
    val generatedAt: String,
    val stores: List<StoreBasketDto>
)

interface ShoppingApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("api/households/")
    suspend fun households(): List<HouseholdDto>

    @POST("api/households/")
    suspend fun createHousehold(@Body body: CreateHouseholdRequest): HouseholdDto

    @POST("api/households/join")
    suspend fun joinHousehold(@Body body: JoinHouseholdRequest): HouseholdDto

    @POST("api/households/{id}/invite")
    suspend fun createInvite(@Path("id") householdId: String): InviteDto

    @GET("api/households/{id}/lists")
    suspend fun lists(@Path("id") householdId: String): List<ShoppingListSummaryDto>

    @POST("api/households/{id}/lists")
    suspend fun createList(@Path("id") householdId: String, @Body body: CreateListRequest): ShoppingListSummaryDto

    @GET("api/lists/{id}")
    suspend fun list(@Path("id") listId: String): ShoppingListDto

    @GET("api/lists/{id}/price-comparison")
    suspend fun priceComparison(
        @Path("id") listId: String,
        @Query("stores") stores: String = "Lidl,SPAR"
    ): BasketComparisonDto

    @POST("api/lists/{id}/items")
    suspend fun addItem(@Path("id") listId: String, @Body body: CreateItemRequest): ShoppingItemDto

    @PATCH("api/items/{id}")
    suspend fun updateItem(@Path("id") itemId: String, @Body body: UpdateItemRequest): ShoppingItemDto

    @DELETE("api/items/{id}")
    suspend fun deleteItem(@Path("id") itemId: String)

    @GET("api/products/search")
    suspend fun searchProducts(@Query("q") query: String, @Query("stores") stores: String? = null): List<ProductOfferDto>
}

class ApiClient(private val tokenProvider: () -> String?) {
    val api: ShoppingApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = tokenProvider()
                val request = chain.request().newBuilder().apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ShoppingApi::class.java)
    }
}
