package com.github.lodestone.data.local.account

import com.github.lodestone.domain.model.account.MinecraftAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/** Everything the launcher remembers about who can play: the accounts, and which one is current. */
@Serializable
data class StoredAccounts(
    val accounts: List<MinecraftAccount> = emptyList(),
    val activeUuid: String? = null,
)

/**
 * Persists [StoredAccounts] as a single encrypted blob.
 *
 * A file rather than DataStore: the payload is one opaque ciphertext that is always read and
 * written whole, so DataStore's per-key merging and type safety buy nothing, and its preference
 * file would additionally have to be excluded from backup by name. A file also keeps this testable
 * off-device. Writes go through a sibling `.tmp` and a rename so a process death mid-write cannot
 * leave a half-written blob, which — being ciphertext — would be indistinguishable from tampering.
 */
class AccountStore(
    private val file: File,
    private val cipher: TokenCipher,
    private val json: Json,
) {

    private val mutex = Mutex()

    suspend fun load(): StoredAccounts = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.isFile) {
                return@withLock StoredAccounts()
            }
            runCatching { json.decodeFromString<StoredAccounts>(String(cipher.decrypt(file.readBytes()))) }
                .getOrElse { failure ->
                    // Reached when the key store entry is gone, so there is nothing to recover and
                    // no point keeping a blob that can never be read again. The failure is logged
                    // by type only: its message can quote the payload.
                    Timber.w("Discarding an unreadable account store (%s)", failure.javaClass.simpleName)
                    file.delete()
                    StoredAccounts()
                }
        }
    }

    suspend fun save(accounts: StoredAccounts): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (accounts.accounts.isEmpty()) {
                file.delete()
                return@withLock
            }
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeBytes(cipher.encrypt(json.encodeToString(accounts).toByteArray()))
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("Could not replace the account store")
            }
        }
    }
}
