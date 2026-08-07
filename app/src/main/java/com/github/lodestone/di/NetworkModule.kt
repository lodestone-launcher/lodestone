package com.github.lodestone.di

import com.github.lodestone.BuildConfig
import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import timber.log.Timber
import java.util.concurrent.TimeUnit

@ContributesTo(AppScope::class)
interface NetworkModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideJson(): Json = LodestoneJson

    /**
     * One client for the whole app.
     *
     * Sharing it matters more here than in a typical app: installing a version opens thousands of
     * requests to the same few hosts, and a shared connection pool is what keeps that from becoming
     * a TLS handshake per asset.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        // Mojang's services answer with a body describing the problem on 4xx, and we want to read
        // it rather than have Ktor throw before we can.
        expectSuccess = false

        engine {
            config {
                connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                retryOnConnectionFailure(true)
            }
        }

        install(ContentNegotiation) { json(json) }

        install(ContentEncoding) {
            // Version and asset manifests are large and highly compressible; the objects
            // themselves are already compressed, and the server simply declines to encode those.
            gzip()
            deflate()
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 30_000
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
        }

        install(UserAgent) {
            // Mojang rate-limits by user agent, so identifying honestly is in our interest.
            agent = "Lodestone/${BuildConfig.VERSION_NAME}"
        }

        if (BuildConfig.DEBUG) {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) = Timber.tag("Http").v(message)
                }
                // HEADERS rather than BODY: request bodies on the sign-in path carry live tokens.
                level = LogLevel.HEADERS
            }
        }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideMicrosoftAuthApi(client: HttpClient, json: Json): MicrosoftAuthApi =
        MicrosoftAuthApi(client, json)

    private companion object {
        const val MAX_IDLE_CONNECTIONS = 16
        const val KEEP_ALIVE_MINUTES = 5L
    }
}
