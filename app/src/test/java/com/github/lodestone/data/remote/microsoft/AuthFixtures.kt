package com.github.lodestone.data.remote.microsoft

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * What each leg of the Microsoft → Xbox → Minecraft chain answers, shaped like the real responses
 * and shared by everything that drives sign-in offline.
 */
internal object AuthFixtures {

    const val MICROSOFT_TOKEN =
        """{"token_type":"bearer","expires_in":86400,"access_token":"a-microsoft-token",""" +
            """"refresh_token":"a-refresh-token"}"""

    const val XBOX_TOKEN =
        """{"Token":"an-xbox-token","DisplayClaims":{"xui":[{"uhs":"a-user-hash"}]}}"""

    /** The XSTS leg is the one that carries the xuid the game is launched with. */
    const val XSTS_TOKEN =
        """{"Token":"an-xsts-token","DisplayClaims":{"xui":[{"uhs":"a-user-hash",""" +
            """"xid":"2535000000000001"}]}}"""

    const val MINECRAFT_LOGIN =
        """{"username":"an-account-id","access_token":"minecraft-access-token",""" +
            """"token_type":"Bearer","expires_in":86400}"""

    fun profile(name: String, uuid: String, skinUrl: String? = null): String {
        val skins = skinUrl?.let { ""","skins":[{"id":"1","state":"ACTIVE","url":"$it"}]""" }.orEmpty()
        return """{"id":"$uuid","name":"$name"$skins}"""
    }
}

/** Serves a body Ktor's content negotiation will actually parse rather than treat as text. */
internal fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
