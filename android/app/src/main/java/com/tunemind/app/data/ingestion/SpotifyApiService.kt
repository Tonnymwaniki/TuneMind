package com.tunemind.app.data.ingestion

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Retrofit API interface for Spotify OAuth 2.0 Authorization Code Token Endpoint
 * (`https://accounts.spotify.com/api/token`).
 */
interface SpotifyAccountsAuthService {

    /**
     * Exchanges an OAuth 2.0 Authorization Code for an Access Token and Refresh Token
     * using `Authorization: Basic Base64(CLIENT_ID:CLIENT_SECRET)`.
     */
    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeAuthorizationCodeWithSecret(
        @Header("Authorization") basicAuthHeader: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String
    ): Response<SpotifyTokenResponse>

    /**
     * Refreshes an expired Access Token using the Refresh Token and
     * `Authorization: Basic Base64(CLIENT_ID:CLIENT_SECRET)`.
     */
    @FormUrlEncoded
    @POST("api/token")
    suspend fun refreshAccessTokenWithSecret(
        @Header("Authorization") basicAuthHeader: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String
    ): Response<SpotifyTokenResponse>

    /**
     * Exchanges an OAuth 2.0 Authorization Code + PKCE `code_verifier` for an Access Token and Refresh Token.
     */
    @FormUrlEncoded
    @POST("api/token")
    suspend fun exchangeAuthorizationCode(
        @Field("client_id") clientId: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("code_verifier") codeVerifier: String
    ): Response<SpotifyTokenResponse>

    /**
     * Silently refreshes an expired Access Token in the background using the stored Refresh Token.
     */
    @FormUrlEncoded
    @POST("api/token")
    suspend fun refreshAccessToken(
        @Field("client_id") clientId: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String
    ): Response<SpotifyTokenResponse>
}

/**
 * Retrofit API interface for the Spotify Web API (`https://api.spotify.com/`).
 *
 * Scopes required:
 * - `user-read-recently-played`
 * - `user-read-currently-playing`
 * - `user-top-read`
 * - `user-modify-playback-state`
 */
interface SpotifyWebApiService {

    /**
     * Fetches the current user's recently played tracks (`GET https://api.spotify.com/v1/me/player/recently-played`).
     *
     * @param authHeader `Authorization: Bearer {token}` header.
     * @param limit Maximum number of items to return (1..50, default 50).
     * @param afterEpochMs Unix timestamp in milliseconds. Returns all items after (but not including) this cursor position.
     * @param beforeEpochMs Unix timestamp in milliseconds. Returns all items before (but not including) this cursor position.
     */
    @GET("v1/me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Header("Authorization") authHeader: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("after") afterEpochMs: Long? = null,
        @Query("before") beforeEpochMs: Long? = null
    ): Response<SpotifyRecentlyPlayedResponse>

    /**
     * Fetches the object currently being played on the user's Spotify account (`GET /v1/me/player/currently-playing`).
     * Note: Returns HTTP 204 No Content if no track is currently playing.
     */
    @GET("v1/me/player/currently-playing")
    suspend fun getCurrentlyPlaying(
        @Header("Authorization") authHeader: String? = null
    ): Response<SpotifyCurrentlyPlayingResponse>

    /**
     * Fetches the current user's top tracks (`GET /v1/me/top/tracks`) based on calculated affinity.
     *
     * @param timeRange Over what time frame the affinities are computed (`short_term`, `medium_term`, `long_term`).
     * @param limit The maximum number of items to return (1..50, default 30).
     */
    @GET("v1/me/top/tracks")
    suspend fun getTopTracks(
        @Header("Authorization") authHeader: String? = null,
        @Query("time_range") timeRange: String = "short_term",
        @Query("limit") limit: Int = 30
    ): Response<SpotifyTopTracksResponse>
}

/**
 * Factory for constructing the Spotify Auth and Web API Retrofit clients.
 */
object SpotifyApiFactory {
    private const val ACCOUNTS_BASE_URL = "https://accounts.spotify.com/"
    private const val WEB_API_BASE_URL = "https://api.spotify.com/"

    val accountsAuthService: SpotifyAccountsAuthService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(ACCOUNTS_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyAccountsAuthService::class.java)
    }

    val directWebApiService: SpotifyWebApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(WEB_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyWebApiService::class.java)
    }

    fun createWebApiService(tokenManager: SpotifyTokenManager): SpotifyWebApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(SpotifyAuthInterceptor(tokenManager, accountsAuthService))
            .build()

        return Retrofit.Builder()
            .baseUrl(WEB_API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyWebApiService::class.java)
    }
}
