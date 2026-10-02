package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Dialogs to create and open the vault, shared by the home screen and the editor. */
object VaultUi {

    /** Runs [then] once the vault is open, creating or opening it first when needed. */
    fun ensureOpen(activity: Activity, store: NoteStore, onCancel: () -> Unit = {}, then: () -> Unit) {
        when {
            Vault.isOpen() -> then()
            !Vault.isSetUp(store) -> setUp(activity, store, onCancel, then)
            else -> open(activity, store, onCancel, then)
        }
    }

    private fun passwordField(activity: Activity, hintRes: Int): EditText =
        EditText(activity).apply {
            hint = activity.getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
        }

    private fun box(activity: Activity): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(activity, 20f)
            setPadding(pad, pad / 2, pad, 0)
        }

    fun setUp(activity: Activity, store: NoteStore, onCancel: () -> Unit = {}, onDone: () -> Unit) {
        val p = Ui.palette(activity)
        val warning = Ui.text(activity, 13f, Ui.RED).apply {
            text = activity.getString(R.string.vault_warning)
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, Ui.dp(activity, 12f))
        }
        val first = passwordField(activity, R.string.vault_password)
        val second = passwordField(activity, R.string.vault_password_again)
        val hint = EditText(activity).apply {
            hint = activity.getString(R.string.vault_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val content = box(activity).apply {
            addView(warning)
            addView(first)
            addView(second)
            addView(hint)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.vault_setup)
            .setView(content)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.save, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val ok: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val password = first.text.toString()
                when {
                    password.length < 6 ->
                        Toast.makeText(activity, R.string.vault_short, Toast.LENGTH_SHORT).show()
                    password != second.text.toString() ->
                        Toast.makeText(activity, R.string.vault_mismatch, Toast.LENGTH_SHORT).show()
                    else -> {
                        ok.isEnabled = false
                        ok.text = activity.getString(R.string.vault_working)
                        val hintText = hint.text.toString().trim()
                        Thread {
                            val success = try {
                                Vault.setUp(store, password, hintText)
                                true
                            } catch (e: Exception) {
                                false
                            }
                            activity.runOnUiThread {
                                if (activity.isDestroyed) return@runOnUiThread
                                if (success) {
                                    dialog.dismiss()
                                    Toast.makeText(activity, R.string.vault_ready, Toast.LENGTH_SHORT).show()
                                    onDone()
                                } else {
                                    ok.isEnabled = true
                                    ok.text = activity.getString(R.string.save)
                                    Toast.makeText(activity, R.string.error_save, Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                }
            }
        }
        dialog.show()
        p.hashCode()
    }

    fun open(activity: Activity, store: NoteStore, onCancel: () -> Unit = {}, onOpen: () -> Unit) {
        val p = Ui.palette(activity)
        val config = Vault.config(store)
        val field = passwordField(activity, R.string.vault_password)
        val content = box(activity)
        val message = Ui.text(activity, 13f, p.muted).apply {
            text = activity.getString(R.string.locked_open_hint)
            setPadding(0, 0, 0, Ui.dp(activity, 8f))
        }
        content.addView(message)
        if (config != null && config.hint.isNotBlank()) {
            content.addView(TextView(activity).apply {
                text = activity.getString(R.string.vault_hint_label, config.hint)
                setTextColor(p.muted)
                textSize = 12f
                setPadding(0, 0, 0, Ui.dp(activity, 8f))
            })
        }
        content.addView(field)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.vault_open)
            .setView(content)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.vault_open, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val ok: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val password = field.text.toString()
                if (password.isEmpty()) return@setOnClickListener
                ok.isEnabled = false
                ok.text = activity.getString(R.string.vault_working)
                Thread {
                    val success = try {
                        Vault.open(store, password)
                    } catch (e: Exception) {
                        false
                    }
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        if (success) {
                            dialog.dismiss()
                            onOpen()
                        } else {
                            ok.isEnabled = true
                            ok.text = activity.getString(R.string.vault_open)
                            field.setText("")
                            Toast.makeText(activity, R.string.vault_wrong, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }
}
