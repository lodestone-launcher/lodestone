package com.github.lodestone.data.local.account

import com.github.lodestone.common.LodestoneJson
import com.github.lodestone.domain.model.account.MinecraftAccount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** Persistence, as it happens on disk: what is written, what comes back, and what is refused. */
class AccountStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var file: File
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        file = File(temporary.newFolder("files"), "accounts.bin")
        store = AccountStore(file, TokenCipher { key }, LodestoneJson)
    }

    @Test
    fun `round trips accounts and the active one`() = runTest {
        val state = StoredAccounts(listOf(account("one"), account("two")), activeUuid = account("two").uuid)

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun `nothing stored yet is not an error`() = runTest {
        assertEquals(StoredAccounts(), store.load())
    }

    @Test
    fun `writes no token in the clear`() = runTest {
        store.save(StoredAccounts(listOf(account("one"))))

        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the refresh token is readable on disk", onDisk.contains("refresh-one"))
        assertFalse("the access token is readable on disk", onDisk.contains("access-one"))
        // No `.tmp` may survive a completed write, or the plaintext-free property above would only
        // hold for the file that was checked.
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun `discards a store it cannot decrypt`() = runTest {
        store.save(StoredAccounts(listOf(account("one"))))
        val other = AccountStore(
            file,
            TokenCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            LodestoneJson,
        )

        // A key store entry that is gone is not recoverable, so the only sane outcome is an empty
        // launcher that asks for a new sign-in rather than a permanent failure to start.
        assertEquals(StoredAccounts(), other.load())
        assertFalse("the unreadable store was kept", file.exists())
    }

    @Test
    fun `saving an empty list removes the file`() = runTest {
        store.save(StoredAccounts(listOf(account("one"))))
        assertTrue(file.isFile)

        store.save(StoredAccounts())

        assertFalse("signing out left the tokens on disk", file.exists())
    }

    private fun account(name: String) = MinecraftAccount(
        uuid = name.repeat(8).take(32),
        username = name,
        accessToken = "access-$name",
        expiresAt = 1_700_000_000_000,
        xuid = "2535000000000000",
        refreshToken = "refresh-$name",
    )
}
