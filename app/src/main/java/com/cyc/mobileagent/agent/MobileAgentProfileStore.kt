package com.cyc.mobileagent.agent
 
import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.Locale
 
data class MobileAgentProfile(
    val schemaVersion: Int = 1,
    val contacts: MutableList<ContactProfile> = mutableListOf()
)
 
data class ContactProfile(
    val platform: String = "wechat",
    var displayName: String = "",
    val aliases: MutableList<String> = mutableListOf()
)
 
enum class BindAliasStatus {
    CREATED_NEW_CONTACT,
    ADDED_TO_EXISTING_CONTACT,
    ALREADY_BOUND,
    CONFLICT
}
 
data class BindAliasResult(
    val status: BindAliasStatus,
    val alias: String,
    val displayName: String,
    val conflictingDisplayName: String? = null
)
 
data class ContactResolveResult(
    val requestedName: String,
    val resolvedDisplayName: String,
    val matchedByAlias: Boolean
)
 
enum class UpdateContactNameStatus {
    UPDATED,
    UNCHANGED,
    NOT_FOUND,
    NAME_CONFLICT
}
 
data class UpdateContactNameResult(
    val status: UpdateContactNameStatus,
    val oldDisplayName: String,
    val newDisplayName: String,
    val conflictingDisplayName: String? = null
)
 
class MobileAgentProfileStore(
    context: Context
) {
 
    companion object {
        private const val FILE_NAME =
            "mobile_agent_profile.json"
 
        private const val CURRENT_SCHEMA_VERSION =
            1
 
        private const val PLATFORM_WECHAT =
            "wechat"
    }
 
    private val appContext =
        context.applicationContext
 
    private val profileFile =
        File(
            appContext.filesDir,
            FILE_NAME
        )
 
    private val gson: Gson =
        GsonBuilder()
            .setPrettyPrinting()
            .create()
 
    init {
        ensureProfileFileExists()
    }
 
    @Synchronized
    fun bindWechatAlias(
        alias: String,
        displayName: String
    ): BindAliasResult {
        val cleanAlias =
            alias.trim()
 
        val cleanDisplayName =
            displayName.trim()
 
        require(cleanAlias.isNotBlank()) {
            "联系人别名不能为空"
        }
 
        require(cleanDisplayName.isNotBlank()) {
            "微信联系人名称不能为空"
        }
 
        val profile =
            readProfile()
 
        val normalizedAlias =
            normalize(cleanAlias)
 
        val normalizedDisplayName =
            normalize(cleanDisplayName)
 
        val aliasOwner =
            profile.contacts.firstOrNull { contact ->
                contact.platform == PLATFORM_WECHAT &&
                    contact.aliases.any {
                        normalize(it) == normalizedAlias
                    }
            }
 
        if (aliasOwner != null) {
            return if (
                normalize(aliasOwner.displayName) ==
                normalizedDisplayName
            ) {
                BindAliasResult(
                    status =
                        BindAliasStatus.ALREADY_BOUND,
                    alias =
                        cleanAlias,
                    displayName =
                        aliasOwner.displayName
                )
            } else {
                BindAliasResult(
                    status =
                        BindAliasStatus.CONFLICT,
                    alias =
                        cleanAlias,
                    displayName =
                        cleanDisplayName,
                    conflictingDisplayName =
                        aliasOwner.displayName
                )
            }
        }
 
        val existingContact =
            profile.contacts.firstOrNull { contact ->
                contact.platform == PLATFORM_WECHAT &&
                    normalize(contact.displayName) ==
                    normalizedDisplayName
            }
 
        if (existingContact != null) {
            existingContact.aliases.add(
                cleanAlias
            )
 
            writeProfile(profile)
 
            return BindAliasResult(
                status =
                    BindAliasStatus.ADDED_TO_EXISTING_CONTACT,
                alias =
                    cleanAlias,
                displayName =
                    existingContact.displayName
            )
        }
 
        profile.contacts.add(
            ContactProfile(
                platform =
                    PLATFORM_WECHAT,
                displayName =
                    cleanDisplayName,
                aliases =
                    mutableListOf(cleanAlias)
            )
        )
 
        writeProfile(profile)
 
        return BindAliasResult(
            status =
                BindAliasStatus.CREATED_NEW_CONTACT,
            alias =
                cleanAlias,
            displayName =
                cleanDisplayName
        )
    }
 
    @Synchronized
    fun resolveWechatContact(
        contactOrAlias: String
    ): ContactResolveResult {
        val cleanValue =
            contactOrAlias.trim()
 
        if (cleanValue.isBlank()) {
            return ContactResolveResult(
                requestedName = "",
                resolvedDisplayName = "",
                matchedByAlias = false
            )
        }
 
        val profile =
            readProfile()
 
        val normalizedValue =
            normalize(cleanValue)
 
        val directContact =
            profile.contacts.firstOrNull { contact ->
                contact.platform == PLATFORM_WECHAT &&
                    normalize(contact.displayName) ==
                    normalizedValue
            }
 
        if (directContact != null) {
            return ContactResolveResult(
                requestedName =
                    cleanValue,
                resolvedDisplayName =
                    directContact.displayName,
                matchedByAlias =
                    false
            )
        }
 
        val aliasContact =
            profile.contacts.firstOrNull { contact ->
                contact.platform == PLATFORM_WECHAT &&
                    contact.aliases.any {
                        normalize(it) ==
                            normalizedValue
                    }
            }
 
        if (aliasContact != null) {
            return ContactResolveResult(
                requestedName =
                    cleanValue,
                resolvedDisplayName =
                    aliasContact.displayName,
                matchedByAlias =
                    true
            )
        }
 
        return ContactResolveResult(
            requestedName =
                cleanValue,
            resolvedDisplayName =
                cleanValue,
            matchedByAlias =
                false
        )
    }
 
    @Synchronized
    fun getWechatContacts():
        List<ContactProfile> {
 
        return readProfile()
            .contacts
            .filter {
                it.platform ==
                    PLATFORM_WECHAT
            }
            .map { contact ->
                ContactProfile(
                    platform =
                        contact.platform,
                    displayName =
                        contact.displayName,
                    aliases =
                        contact.aliases
                            .toMutableList()
                )
            }
            .sortedBy {
                it.displayName
                    .lowercase(Locale.ROOT)
            }
    }
 
    @Synchronized
    fun updateWechatContactDisplayName(
        currentDisplayName: String,
        newDisplayName: String
    ): UpdateContactNameResult {
        val cleanCurrentName =
            currentDisplayName.trim()
 
        val cleanNewName =
            newDisplayName.trim()
 
        require(cleanCurrentName.isNotBlank()) {
            "原微信名称不能为空"
        }
 
        require(cleanNewName.isNotBlank()) {
            "新微信名称不能为空"
        }
 
        val profile =
            readProfile()
 
        val targetContact =
            profile.contacts.firstOrNull { contact ->
                contact.platform == PLATFORM_WECHAT &&
                    normalize(contact.displayName) ==
                    normalize(cleanCurrentName)
            }
 
        if (targetContact == null) {
            return UpdateContactNameResult(
                status =
                    UpdateContactNameStatus.NOT_FOUND,
                oldDisplayName =
                    cleanCurrentName,
                newDisplayName =
                    cleanNewName
            )
        }
 
        if (
            targetContact.displayName ==
            cleanNewName
        ) {
            return UpdateContactNameResult(
                status =
                    UpdateContactNameStatus.UNCHANGED,
                oldDisplayName =
                    targetContact.displayName,
                newDisplayName =
                    cleanNewName
            )
        }
 
        val conflict =
            profile.contacts.firstOrNull { contact ->
                contact !== targetContact &&
                    contact.platform ==
                    PLATFORM_WECHAT &&
                    normalize(contact.displayName) ==
                    normalize(cleanNewName)
            }
 
        if (conflict != null) {
            return UpdateContactNameResult(
                status =
                    UpdateContactNameStatus.NAME_CONFLICT,
                oldDisplayName =
                    targetContact.displayName,
                newDisplayName =
                    cleanNewName,
                conflictingDisplayName =
                    conflict.displayName
            )
        }
 
        val oldDisplayName =
            targetContact.displayName
 
        targetContact.displayName =
            cleanNewName
 
        writeProfile(profile)
 
        return UpdateContactNameResult(
            status =
                UpdateContactNameStatus.UPDATED,
            oldDisplayName =
                oldDisplayName,
            newDisplayName =
                cleanNewName
        )
    }
 
    @Synchronized
    fun removeWechatAlias(
        alias: String
    ): Boolean {
        val cleanAlias =
            alias.trim()
 
        if (cleanAlias.isBlank()) {
            return false
        }
 
        val profile =
            readProfile()
 
        val normalizedAlias =
            normalize(cleanAlias)
 
        var removed =
            false
 
        profile.contacts.forEach { contact ->
            if (
                contact.platform ==
                PLATFORM_WECHAT
            ) {
                val iterator =
                    contact.aliases.iterator()
 
                while (iterator.hasNext()) {
                    val currentAlias =
                        iterator.next()
 
                    if (
                        normalize(currentAlias) ==
                        normalizedAlias
                    ) {
                        iterator.remove()
                        removed = true
                    }
                }
            }
        }
 
        profile.contacts.removeAll { contact ->
            contact.platform ==
                PLATFORM_WECHAT &&
                contact.aliases.isEmpty()
        }
 
        if (removed) {
            writeProfile(profile)
        }
 
        return removed
    }
 
    @Synchronized
    fun deleteWechatContact(
        displayName: String
    ): Boolean {
        val cleanDisplayName =
            displayName.trim()
 
        if (cleanDisplayName.isBlank()) {
            return false
        }
 
        val profile =
            readProfile()
 
        val removed =
            profile.contacts.removeAll { contact ->
                contact.platform ==
                    PLATFORM_WECHAT &&
                    normalize(contact.displayName) ==
                    normalize(cleanDisplayName)
            }
 
        if (removed) {
            writeProfile(profile)
        }
 
        return removed
    }
 
    @Synchronized
    fun getProfile():
        MobileAgentProfile {
 
        return readProfile()
    }
 
    @Synchronized
    fun getProfileJson():
        String {
 
        return gson.toJson(
            readProfile()
        )
    }
 
    private fun ensureProfileFileExists() {
        if (profileFile.exists()) {
            return
        }
 
        writeProfile(
            createEmptyProfile()
        )
    }
 
    private fun createEmptyProfile():
        MobileAgentProfile {
 
        return MobileAgentProfile(
            schemaVersion =
                CURRENT_SCHEMA_VERSION,
            contacts =
                mutableListOf()
        )
    }
 
    private fun readProfile():
        MobileAgentProfile {
 
        if (!profileFile.exists()) {
            val emptyProfile =
                createEmptyProfile()
 
            writeProfile(emptyProfile)
 
            return emptyProfile
        }
 
        return try {
            val json =
                profileFile.readText(
                    Charsets.UTF_8
                )
 
            if (json.isBlank()) {
                val emptyProfile =
                    createEmptyProfile()
 
                writeProfile(emptyProfile)
 
                emptyProfile
            } else {
                gson.fromJson(
                    json,
                    MobileAgentProfile::class.java
                ) ?: createEmptyProfile()
            }
        } catch (e: Exception) {
            try {
                val backupFile =
                    File(
                        appContext.filesDir,
                        "mobile_agent_profile_broken_" +
                            System.currentTimeMillis() +
                            ".json"
                    )
 
                profileFile.copyTo(
                    target =
                        backupFile,
                    overwrite =
                        true
                )
            } catch (_: Exception) {
            }
 
            val emptyProfile =
                createEmptyProfile()
 
            writeProfile(emptyProfile)
 
            emptyProfile
        }
    }
 
    private fun writeProfile(
        profile: MobileAgentProfile
    ) {
        val json =
            gson.toJson(profile)
 
        val temporaryFile =
            File(
                appContext.filesDir,
                "$FILE_NAME.tmp"
            )
 
        temporaryFile.writeText(
            text =
                json,
            charset =
                Charsets.UTF_8
        )
 
        temporaryFile.copyTo(
            target =
                profileFile,
            overwrite =
                true
        )
 
        temporaryFile.delete()
    }
 
    private fun normalize(
        value: String
    ): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
    }
}
