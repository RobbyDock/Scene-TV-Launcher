package com.robbydock.scene

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** A launcher profile. Each has its own Home settings; the Stremio sign-in is shared. */
class Profile(val id: String, var name: String, var color: Int) {
    /** Salted SHA-256 of the D-pad passcode, empty when the profile is open. */
    var codeHash = ""
    var codeSalt = ""
    /** Picture: one emoji drawn with the system emoji font, or empty for the initial. */
    var emoji = ""
    /** Admins can change any profile. Standard profiles change only their own. */
    var admin = false
    /** PBKDF2 hash and salt of an admin's recovery code, empty when none is set. */
    var recoveryHash = ""
    var recoverySalt = ""
    val locked get() = codeHash.isNotEmpty()
    val hasRecovery get() = recoveryHash.isNotEmpty()
    val initial get() = Guard.initial(name)
}

/**
 * Profile list stored in profiles.json. The first profile uses settings.json.
 */
object Profiles {
    private const val FILE = "profiles.json"
    /** Copy of the last profiles file that loaded, used when the main file is damaged. */
    private const val LAST_GOOD = "profiles.last-good.json"
    /** A damaged profiles file is kept here. */
    private const val BAD = "profiles.bad.json"
    const val MAX = Limits.PROFILES
    /** Passcode length in D-pad presses. */
    const val CODE_LEN = 4
    /** Profile pictures. Drawn with Noto Color Emoji, licensed under the OFL. */
    val EMOJI = listOf(
        "🦊", "🐰", "🐼", "🐱", "🐶", "🦁", "🐯", "🐸", "🐵",
        "🐙", "🦄", "🐧", "🐻", "🐨", "🦉", "🐢", "🐳", "🐹",
        "🚀", "🎮", "🎬", "🎧", "⚽", "🏀", "🎸", "🎨",
        "🌵", "🌻", "🦋", "🍕", "🍩", "⭐", "🌙", "🔥", "👾",
    )

    val list = ArrayList<Profile>()
    var currentId = "p0"
        private set
    /** Show the profile picker when the TV wakes. */
    var askOnWake = false

    val current: Profile get() = list.firstOrNull { it.id == currentId } ?: list.first()

    fun fileFor(id: String) = if (id == "p0") "settings.json" else "settings-$id.json"

    /** A message to show once when profiles had to be recovered, else null. */
    var recovered: String? = null
        private set

    /**
     * Loads profiles. A damaged file is kept aside as profiles.bad.json, and the last good copy is
     * used instead. If that is damaged too, one new profile is made.
     */
    fun load(ctx: Context) {
        recovered = null
        val text = Store.readText(ctx, FILE)
        when {
            text == null -> fresh(FIRST_NAME)
            parse(text) -> {
                if (Store.readText(ctx, LAST_GOOD) != text) Store.writeText(ctx, LAST_GOOD, text)
            }
            else -> {
                Store.keepAside(ctx, FILE, BAD)
                val good = Store.readText(ctx, LAST_GOOD)
                if (good != null && parse(good)) {
                    recovered = "Profiles restored from the last good copy"
                } else {
                    fresh("Profile 1")
                    recovered = "Profiles couldn't be read, so a new profile was made"
                }
                save(ctx)
            }
        }
        Store.file = fileFor(currentId)
    }

    private fun fresh(name: String) {
        list.clear()
        list.add(Profile("p0", name, 0).apply { admin = true })
        currentId = "p0"
        askOnWake = false
    }

    /** Fills the list from [text]. False when the text is damaged or holds no usable profile. */
    private fun parse(text: String): Boolean {
        if (text.length > Limits.BACKUP_BYTES || !Guard.depthOk(text)) return false
        val parsed = ArrayList<Profile>()
        val o = try { JSONObject(text) } catch (e: Exception) { return false }
        val a = o.optJSONArray("profiles") ?: return false
        for (i in 0 until a.length()) {
            if (parsed.size >= MAX) break
            val p = a.optJSONObject(i) ?: continue
            val id = p.optString("id").takeIf { it.matches(ID) } ?: continue
            if (parsed.any { it.id == id }) continue
            val name = Guard.cleanText(p.optString("name"), Limits.PROFILE_NAME).ifEmpty { "Profile ${parsed.size + 1}" }
            parsed.add(Profile(id, name, p.optInt("color", 0).coerceIn(0, Theme.ACCENTS.size - 1)).apply {
                codeHash = p.optString("hash")
                codeSalt = p.optString("salt")
                emoji = p.optString("emoji").takeIf { it in EMOJI } ?: ""
                // The older "owner" flag counts as admin.
                admin = p.optBoolean("admin", p.optBoolean("owner", false))
                if (admin) {
                    recoveryHash = p.optString("rhash")
                    recoverySalt = p.optString("rsalt")
                }
            })
        }
        if (parsed.isEmpty()) return false
        Roles.ensureAdmin(parsed)
        list.clear()
        list.addAll(parsed)
        currentId = o.optString("current", "p0").takeIf { id -> list.any { it.id == id } } ?: list.first().id
        askOnWake = o.optBoolean("askOnWake", false)
        return true
    }

    /** Saves profiles and refreshes the last good copy. False when the write failed. */
    fun save(ctx: Context): Boolean {
        if (Demo.on) return true
        val a = JSONArray()
        for (p in list) {
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color).put("hash", p.codeHash).put("salt", p.codeSalt).put("emoji", p.emoji)
                .put("admin", p.admin).put("rhash", p.recoveryHash).put("rsalt", p.recoverySalt))
        }
        val text = JSONObject().put("profiles", a).put("current", currentId).put("askOnWake", askOnWake).toString()
        return Store.writeText(ctx, FILE, text) && Store.writeText(ctx, LAST_GOOD, text)
    }

    private var realList: List<Profile>? = null
    private var realCurrent = "p0"

    /** Shows [sample] in place of the saved profiles until [endDemo]. */
    fun startDemo(sample: List<Profile>) {
        realList = ArrayList(list)
        realCurrent = currentId
        list.clear()
        list.addAll(sample)
        currentId = sample.first().id
    }

    fun endDemo() {
        val real = realList ?: return
        list.clear()
        list.addAll(real)
        currentId = realCurrent
        Store.file = fileFor(currentId)
        realList = null
    }

    private val ID = Regex("p[0-9]{1,6}")

    /** Renames [p] with the same cleaning as a new profile. False when the cleaned name is empty. */
    fun rename(ctx: Context, p: Profile, name: String): Boolean {
        val clean = Guard.cleanText(name, Limits.PROFILE_NAME)
        if (clean.isEmpty()) return false
        p.name = Guard.uniqueName(clean, list.filter { it !== p }.map { it.name })
        save(ctx)
        return true
    }

    fun select(ctx: Context, id: String) {
        if (list.none { it.id == id }) return
        currentId = id
        Store.file = fileFor(id)
        save(ctx)
    }

    /** The first colour no profile uses yet. */
    fun nextColor(): Int {
        val used = list.map { it.color }.toSet()
        return Theme.ACCENTS.indices.firstOrNull { it !in used } ?: (list.size % Theme.ACCENTS.size)
    }

    /** The name a new profile called [name] would get: cleaned, with a number added if taken. */
    fun nameFor(name: String, except: Profile? = null): String {
        val clean = Guard.cleanText(name, Limits.PROFILE_NAME).ifEmpty { "Profile ${list.size + 1}" }
        return Guard.uniqueName(clean, list.filter { it !== except }.map { it.name })
    }

    /** Adds a standard profile. */
    fun add(ctx: Context, name: String, emoji: String = "", color: Int = nextColor()): Profile {
        var n = 1
        while (list.any { it.id == "p$n" }) n++
        val p = Profile("p$n", nameFor(name), color.coerceIn(0, Theme.ACCENTS.size - 1))
        p.emoji = emoji.takeIf { it in EMOJI } ?: ""
        list.add(p)
        save(ctx)
        return p
    }

    /** True until the first profile has been named in setup. */
    val needsFirstProfile get() = list.size == 1 && list[0].name == FIRST_NAME && !list[0].locked
    private const val FIRST_NAME = "Main"

    /** Applies setup's "Create your profile" to the first profile, which stays an admin. */
    fun setUpFirst(ctx: Context, name: String, emoji: String, color: Int) {
        val p = list.firstOrNull() ?: return
        p.name = nameFor(name, p)
        p.emoji = emoji.takeIf { it in EMOJI } ?: ""
        p.color = color.coerceIn(0, Theme.ACCENTS.size - 1)
        p.admin = true
        save(ctx)
    }

    /** Where Scene lands after deleting the current profile: the first other profile with no passcode. */
    fun landingAfter(id: String): Profile? = list.firstOrNull { it.id != id && !it.locked }

    /**
     * Removes a profile and its settings file. The last profile can't be removed. Deleting the
     * current profile needs an unlocked profile to land on. When the only admin goes, the first
     * remaining profile becomes admin.
     */
    fun remove(ctx: Context, id: String, landOn: Profile? = null): Boolean {
        if (list.size <= 1 || list.none { it.id == id }) return false
        // [landOn] is a profile whose passcode the caller has just checked.
        val landing = if (currentId == id) (landOn?.takeIf { it.id != id && it in list } ?: landingAfter(id) ?: return false) else null
        list.removeAll { it.id == id }
        Roles.ensureAdmin(list)
        Store.delete(ctx, fileFor(id))
        Locks.forget(ctx, id)
        if (landing != null) select(ctx, landing.id) else save(ctx)
        return true
    }

    /** Changes the role of [p]. False when this would leave no admin. */
    fun setAdmin(ctx: Context, p: Profile, admin: Boolean): Boolean {
        if (!Roles.setAdmin(list, p, admin)) return false
        save(ctx)
        return true
    }

    /** Checks a recovery code with the slow hash. */
    fun checkRecovery(p: Profile, input: String) = p.admin && Recovery.matches(input, p.recoverySalt, p.recoveryHash)

    /** Clears every passcode, recovery code and lockout. */
    fun clearSecrets(ctx: Context) {
        for (p in list) { p.codeHash = ""; p.codeSalt = ""; p.recoveryHash = ""; p.recoverySalt = "" }
        save(ctx)
        Locks.clearAll(ctx)
    }

    fun setCode(ctx: Context, p: Profile, code: String?) {
        if (code == null) {
            p.codeHash = ""
            p.codeSalt = ""
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            p.codeSalt = salt
            p.codeHash = hash(salt, code)
        }
        save(ctx)
    }

    fun checkCode(p: Profile, code: String) = p.locked && MessageDigest.isEqual(hash(p.codeSalt, code).toByteArray(), p.codeHash.toByteArray())

    private fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + code).toByteArray()).joinToString("") { "%02x".format(it) }
}
