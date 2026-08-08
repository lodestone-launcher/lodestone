package com.github.lodestone.di

import android.content.Context
import com.github.lodestone.data.local.account.AccountStore
import com.github.lodestone.data.local.account.TokenCipher
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.repository.AccountRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import java.io.File

@ContributesTo(AppScope::class)
interface AccountModule {

    /**
     * The account store sits directly in `filesDir` rather than under the game directory, which is
     * a user-visible Minecraft installation people copy between launchers and back up wholesale.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAccountStore(context: Context, json: Json): AccountStore =
        AccountStore(File(context.filesDir, ACCOUNT_STORE_FILE), TokenCipher.androidKeystore(), json)

    /**
     * A single instance for the whole app: every screen has to see the same active account, and two
     * copies would race each other's writes to one file.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAccountRepository(store: AccountStore, auth: MicrosoftAuthApi): AccountRepository =
        AccountRepository(store, auth)
}

/** Named in `@xml/data_extraction_rules`, which excludes it from backup. */
private const val ACCOUNT_STORE_FILE = "accounts.bin"
