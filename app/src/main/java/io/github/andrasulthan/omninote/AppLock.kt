package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout

/**
 * App lock: asks for the phone's fingerprint, face, PIN or pattern when OmniNote
 * is opened again after being away. Also closes the vault after a longer break.
 */
object AppLock {

    private const val KEY_MODE = "app_lock_mode"
    private const val VAULT_TIMEOUT = 5 * 60_000L

    @Volatile
    var locked = false

    private var started = 0
    private var leftAt = 0L

    /** 0 off, 1 right away (15 s grace), 2 after 1 minute, 3 after 5 minutes. */
    fun mode(ctx: Context): Int =
        ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE).getInt(KEY_MODE, 0)

    fun setMode(ctx: Context, mode: Int) {
        ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode.coerceIn(0, 3)).apply()
    }

    /** App lock needs a screen lock on the phone. */
    fun canUse(ctx: Context): Boolean {
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isDeviceSecure
    }

    private fun limit(mode: Int): Long = when (mode) {
        1 -> 15_000L
        2 -> 60_000L
        else -> 5 * 60_000L
    }

    fun onStarted(activity: Activity) {
        if (started == 0) onForeground(activity)
        started++
    }

    fun onStopped(activity: Activity) {
        if (activity.isChangingConfigurations) return
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) leftAt = SystemClock.elapsedRealtime()
    }

    fun onResumed(activity: Activity) {
        if (locked && activity !is LockActivity) {
            activity.startActivity(Intent(activity, LockActivity::class.java))
        }
    }

    private fun onForeground(ctx: Context) {
        val away = if (leftAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - leftAt
        if (away > VAULT_TIMEOUT) Vault.close()
        val mode = mode(ctx)
        if (mode != 0 && canUse(ctx) && away >= limit(mode)) locked = true
    }
}

/** Starts the app lock watcher for every screen of OmniNote. */
class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppLock.onStarted(activity)
            override fun onActivityStopped(activity: Activity) = AppLock.onStopped(activity)
            override fun onActivityResumed(activity: Activity) = AppLock.onResumed(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/** Black lock screen with the red dot; unlocks with the phone's own screen lock. */
class LockActivity : Activity() {

    private var prompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.palette(this)
        val dot = Ui.dp(this, 10f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(p.bg)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(context, 24f, p.text, bold = true).apply {
                text = getString(R.string.app_name).uppercase()
                letterSpacing = 0.12f
            })
            addView(Ui.dot(context), LinearLayout.LayoutParams(dot, dot).apply { marginStart = dot })
        }
        val subtitle = Ui.text(this, 13f, p.muted).apply {
            text = getString(R.string.lock_title)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 10f), 0, Ui.dp(context, 32f))
        }
        val button = Ui.text(this, 14f, p.bg, bold = true).apply {
            text = getString(R.string.lock_unlock).uppercase()
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            val h = Ui.dp(context, 28f)
            val v = Ui.dp(context, 14f)
            setPadding(h, v, h, v)
            background = Ui.rounded(p.text, p.text, Ui.dp(context, 28f).toFloat())
            setOnClickListener { prompt() }
        }
        root.addView(row)
        root.addView(subtitle)
        root.addView(button)
        Ui.applyInsets(root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.locked) {
            finish()
        } else if (!prompted) {
            prompted = true
            prompt()
        }
    }

    private fun unlocked() {
        AppLock.locked = false
        finish()
    }

    private fun prompt() {
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.lock_title))
                .setSubtitle(getString(R.string.lock_reason))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    unlocked()
                }
            })
        } else {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.lock_title), getString(R.string.lock_reason)
            )
            if (intent == null) {
                unlocked()
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_UNLOCK)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK && resultCode == RESULT_OK) unlocked()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    companion object {
        private const val REQ_UNLOCK = 31
    }
}
