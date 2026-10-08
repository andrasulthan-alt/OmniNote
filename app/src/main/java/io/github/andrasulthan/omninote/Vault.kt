package io.github.andrasulthan.omninote

import java.security.SecureRandom
import java.util.Base64

/**
 * The vault: one password locks chosen notes (an idea from Notesnook and Scarlet Notes).
 * The password check and salt live in ".omninote/vault.txt", so the vault syncs to other
 * phones. The key itself stays in memory only while the vault is open.
 */
object Vault {

    private const val FILE = "vault.txt"

    @Volatile
    private var key: ByteArray? = null

    /** A vault (or a locked note) already exists in this notes folder. */
    class ExistsException : IllegalStateException("Vault already exists")

    class Config(val salt: ByteArray, val check: String, val hint: String)

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun config(store: NoteStore): Config? {
        val text = try {
            store.readHidden(FILE)
        } catch (e: Exception) {
            null
        } ?: return null
        val values = HashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i > 0) values[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return try {
            val salt = decode(values["salt"] ?: return null)
            val check = values["check"] ?: return null
            val hint = values["hint"]?.let { String(decode(it), Charsets.UTF_8) } ?: ""
            Config(salt, check, hint)
        } catch (e: Exception) {
            null
        }
    }

    fun isSetUp(store: NoteStore): Boolean = config(store) != null

    fun isOpen(): Boolean = key != null

    /** Forgets the key; locked notes need the password again. */
    fun close() {
        key?.fill(0)
        key = null
    }

    /** Creates the vault. Slow on purpose (Argon2id), so call it off the main thread. */
    fun setUp(store: NoteStore, password: String, hint: String) {
        // Never replace an existing vault: a new salt would make every locked note unreadable,
        // also on synced devices. A read error here stops the setup instead of guessing.
        if (store.readHidden(FILE) != null || store.list().any { it.meta.vault }) throw ExistsException()
        val salt = VaultCrypto.newSalt()
        val newKey = VaultCrypto.deriveKey(password, salt)
        val check = VaultCrypto.makeCheck(newKey)
        val text = "version=1\n" +
            "salt=" + encode(salt) + "\n" +
            "check=" + check + "\n" +
            "hint=" + encode(hint.toByteArray(Charsets.UTF_8)) + "\n"
        store.writeHidden(FILE, text)
        key = newKey
    }

    /** Opens the vault when the password is right. Slow on purpose, call it off the main thread. */
    fun open(store: NoteStore, password: String): Boolean {
        val c = config(store) ?: return false
        val tryKey = VaultCrypto.deriveKey(password, c.salt)
        return if (VaultCrypto.verify(tryKey, c.check)) {
            key = tryKey
            true
        } else {
            tryKey.fill(0)
            false
        }
    }

    fun isLocked(fileText: String): Boolean = NoteMeta.parse(fileText).first.vault

    /** Encrypts a whole note (properties and text) into the text of a locked file. */
    fun seal(plain: String): String {
        val k = key ?: throw IllegalStateException("Vault is closed")
        return NoteMeta.build(NoteMeta(vault = true), VaultCrypto.encrypt(k, plain) + "\n")
    }

    /** Gives back the original note text of a locked file. */
    fun unseal(fileText: String): String {
        val k = key ?: throw IllegalStateException("Vault is closed")
        val (_, content) = NoteMeta.parse(fileText)
        return VaultCrypto.decrypt(k, content.trim())
    }

    /** A file name that does not reveal the title of a locked note. */
    fun lockedTitle(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return "Locked " + bytes.joinToString("") { "%02x".format(it) }
    }
}
