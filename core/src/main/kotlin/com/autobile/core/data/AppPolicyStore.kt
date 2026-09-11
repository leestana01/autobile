package com.autobile.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.autobile.core.model.AppCategory
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Per-application automation policy.
 *
 * A package with no stored row is not implicitly allowed: [policyFor] falls back to
 * the classifier's category default, and a package the classifier does not recognise
 * resolves to [AppPolicyMode.ASK] rather than [AppPolicyMode.ALLOW].
 */
class AppPolicyStore(private val db: AutobileDatabase) {

    private val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    fun observe(): Flow<List<AppPolicy>> = changes.onStart { emit(Unit) }.map { list() }

    suspend fun list(): List<AppPolicy> = withContext(Dispatchers.IO) {
        val out = mutableListOf<AppPolicy>()
        db.readableDatabase.rawQuery(
            "SELECT package_name, mode, category, user_set, label FROM app_policies ORDER BY package_name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += AppPolicy(
                    packageName = cursor.getString(0),
                    mode = AppPolicyMode.valueOf(cursor.getString(1)),
                    category = AppCategory.valueOf(cursor.getString(2)),
                    userSet = cursor.getInt(3) == 1,
                    label = cursor.getString(4),
                )
            }
        }
        out
    }

    suspend fun policyFor(packageName: String): AppPolicy = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT package_name, mode, category, user_set, label FROM app_policies WHERE package_name = ?",
            arrayOf(packageName),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                AppPolicy(
                    packageName = cursor.getString(0),
                    mode = AppPolicyMode.valueOf(cursor.getString(1)),
                    category = AppCategory.valueOf(cursor.getString(2)),
                    userSet = cursor.getInt(3) == 1,
                    label = cursor.getString(4),
                )
            } else {
                val category = AppCategoryClassifier.classify(packageName)
                AppPolicy(packageName, category.defaultMode, category, userSet = false)
            }
        }
    }

    suspend fun save(policy: AppPolicy) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "app_policies",
            null,
            ContentValues().apply {
                put("package_name", policy.packageName)
                put("mode", policy.mode.name)
                put("category", policy.category.name)
                put("user_set", if (policy.userSet) 1 else 0)
                put("label", policy.label)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    /** Writes protective defaults for the installed packages the classifier recognises. */
    suspend fun seedDefaults(installedPackages: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val existing = list().associateBy { it.packageName }
        installedPackages.forEach { (pkg, label) ->
            if (existing.containsKey(pkg)) return@forEach
            val category = AppCategoryClassifier.classify(pkg)
            if (category == AppCategory.OTHER) return@forEach
            save(AppPolicy(pkg, category.defaultMode, category, userSet = false, label = label))
        }
    }
}

/**
 * Heuristic package-name to category mapping, used to apply protective defaults before
 * the user has configured anything.
 *
 * Matching is substring-based and therefore imprecise, which is acceptable because it
 * only ever fails toward caution: an unrecognised package becomes [AppCategory.OTHER],
 * whose default is to ask rather than to allow.
 */
object AppCategoryClassifier {

    private val bankingHints = listOf(
        "bank", "shinhan", "kbstar", "kbbank", "wooribank", "nonghyup", "nhbank", "hanabank",
        "ibk", "kakaobank", "tossbank", "kbankwith", "citi", "scbank", "paypal", "wise",
        "finance", "securities", "stock", "invest", "crypto", "upbit", "bithumb", "binance",
        "coinbase", "card", "samsungcard", "hyundaicard", "lottecard", "bccard",
    )
    private val walletHints = listOf("toss", "kakaopay", "payco", "naverpay", "samsungpay", "wallet")
    private val passwordHints = listOf("1password", "lastpass", "bitwarden", "dashlane", "keepass", "enpass", "keeper")
    private val authenticatorHints = listOf("authenticator", "otp", "duo", "authy", "yubico", "passkey")
    private val healthHints = listOf("health", "fitness", "samsunghealth", "fitbit", "medical", "hospital", "clinic")
    private val galleryHints = listOf("gallery", "photos", "camera", "album")
    private val messagingHints = listOf(
        "kakao.talk", "whatsapp", "telegram", "line", "messenger", "signal", "slack",
        "discord", "wechat", "mms", "messaging", "sms",
    )
    private val businessHints = listOf("slack", "teams", "notion", "jira", "salesforce", "workspace", "erp", "pos")

    fun classify(packageName: String): AppCategory {
        val p = packageName.lowercase()
        return when {
            passwordHints.any { p.contains(it) } -> AppCategory.PASSWORD_MANAGER
            authenticatorHints.any { p.contains(it) } -> AppCategory.AUTHENTICATOR
            bankingHints.any { p.contains(it) } || walletHints.any { p.contains(it) } -> AppCategory.BANKING
            healthHints.any { p.contains(it) } -> AppCategory.HEALTH
            galleryHints.any { p.contains(it) } -> AppCategory.GALLERY
            messagingHints.any { p.contains(it) } -> AppCategory.MESSAGING
            businessHints.any { p.contains(it) } -> AppCategory.BUSINESS
            else -> AppCategory.OTHER
        }
    }
}
