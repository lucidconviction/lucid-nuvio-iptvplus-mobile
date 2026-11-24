// Adapted from Laking22/Nuvio-Echo (e95241955), GPL-3.0. See the repository LICENSE.
@file:Suppress("unused")

package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.UiText

/**
 * CloudStream's tracker accounts (AniList, Simkl), as extensions see them. ReSeq has its own Trakt, Simkl and
 * MDBList sign-ins, so these never have a signed-in user: an extension asking for one gets none, and carries on
 * as CloudStream does for someone not signed in.
 */
abstract class SyncAPI {
    open val name: String = ""
    open val idPrefix: String = ""
    open val mainUrl: String = ""

    data class LibraryList(
        val name: UiText,
        val items: List<SearchResponse>
    )

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<Any> = emptySet()
    )
}

data class AuthUser(
    val name: String?,
    val id: Int,
    val profilePicture: String? = null,
    val profilePictureHeaders: Map<String, String>? = null
)

class SyncRepo(val api: SyncAPI) {
    val name: String get() = api.name
    val idPrefix: String get() = api.idPrefix

    fun authUser(): AuthUser? = null

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = Result.success(null)
}

/** CloudStream's account API, used by extensions built before and after its 2025 account rework. */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"
    open val icon: Int? = null
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null

    data class LoginInfo(
        val profilePicture: String? = null,
        val name: String?,
        val accountIndex: Int,
    )
}

data class AuthToken(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenLifetime: Long? = null,
    val refreshTokenLifetime: Long? = null,
    val payload: String? = null,
)

data class AuthData(
    val user: AuthUser,
    val token: AuthToken,
)

/** Before CloudStream's 2025 account rework: a service signed in to from a dialog in the app. */
interface InAppAuthAPI {
    data class LoginData(
        val username: String? = null,
        val password: String? = null,
        val server: String? = null,
        val email: String? = null,
    )

    val requiresPassword: Boolean
    val requiresUsername: Boolean
    val requiresServer: Boolean
    val requiresEmail: Boolean
    val storesPasswordInPlainText: Boolean

    suspend fun login(data: LoginData): Boolean

    fun getLatestLoginData(): LoginData?
}

abstract class AccountManager(private val defIndex: Int) {
    companion object {
        const val NONE_ID: Int = -1

        @JvmStatic
        val aniListApi = com.lagradost.cloudstream3.syncproviders.providers.AniListApi()

        @JvmStatic
        val simklApi = com.lagradost.cloudstream3.syncproviders.providers.SimklApi()

        @JvmStatic
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MALApi()

        /** Old extensions list their dialog-based accounts here; we never sign in to them. */
        val inAppAuths: List<InAppAuthAPIManager>
            get() = emptyList()
    }

    abstract val idPrefix: String
    abstract fun loginInfo(): AuthAPI.LoginInfo?

    var accountIndex = defIndex
    private var lastAccountIndex = defIndex
    protected val accountId get() = "${idPrefix}_account_$accountIndex"
    private val accountActiveKey get() = "${idPrefix}_active"
    private val accountsKey get() = "${idPrefix}_accounts"

    protected fun removeAccountKeys() {
        com.lagradost.cloudstream3.CloudStreamApp.removeKeys(accountId)
        val accounts = getAccounts()?.toMutableList() ?: mutableListOf()
        accounts.remove(accountIndex)
        com.lagradost.cloudstream3.CloudStreamApp.setKey(accountsKey, accounts.toIntArray())
        init()
    }

    fun getAccounts(): IntArray? =
        com.lagradost.cloudstream3.CloudStreamApp.getKey(accountsKey, intArrayOf())

    fun init() {
        accountIndex = com.lagradost.cloudstream3.CloudStreamApp.getKey(accountActiveKey, defIndex) ?: defIndex
        val accounts = getAccounts()
        if (accounts?.isNotEmpty() == true && this.loginInfo() == null) {
            accountIndex = accounts.first()
        }
    }

    protected fun switchToNewAccount() {
        val accounts = getAccounts()
        lastAccountIndex = accountIndex
        accountIndex = (accounts?.maxOrNull() ?: 0) + 1
    }

    protected fun switchToOldAccount() {
        accountIndex = lastAccountIndex
    }

    protected fun registerAccount() {
        com.lagradost.cloudstream3.CloudStreamApp.setKey(accountActiveKey, accountIndex)
        val accounts = getAccounts()?.toMutableList() ?: mutableListOf()
        if (!accounts.contains(accountIndex)) {
            accounts.add(accountIndex)
        }
        com.lagradost.cloudstream3.CloudStreamApp.setKey(accountsKey, accounts.toIntArray())
    }

    fun changeAccount(index: Int) {
        accountIndex = index
        com.lagradost.cloudstream3.CloudStreamApp.setKey(accountActiveKey, index)
    }
}

abstract class InAppAuthAPIManager(defIndex: Int) : AccountManager(defIndex), InAppAuthAPI {
    override val requiresPassword = false
    override val requiresUsername = false
    override val requiresEmail = false
    override val requiresServer = false
    override val storesPasswordInPlainText = true
    open val requiresLogin = true
    open val createAccountUrl: String? = null
    open val icon: Int? = null

    open val name: String
        get() = throw NotImplementedError()

    override val idPrefix: String
        get() = throw NotImplementedError()

    open suspend fun initialize() {
    }

    open fun logOut() {
        throw NotImplementedError()
    }

    override suspend fun login(data: InAppAuthAPI.LoginData): Boolean = throw NotImplementedError()

    override fun getLatestLoginData(): InAppAuthAPI.LoginData? = throw NotImplementedError()

    override fun loginInfo(): AuthAPI.LoginInfo? = throw NotImplementedError()
}
