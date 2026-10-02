package io.github.andrasulthan.omninote

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Nothing-style look shared by every screen.
 * Palette adapted from nothing.notes by ThriveEngineer (MIT), rewritten in Kotlin.
 */
object Ui {

    val RED: Int = 0xFFD71921.toInt()

    class Palette(
        val bg: Int,
        val surface: Int,
        val border: Int,
        val text: Int,
        val muted: Int
    )

    fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun palette(ctx: Context): Palette =
        if (isNight(ctx)) {
            Palette(
                bg = 0xFF000000.toInt(),
                surface = 0xFF111111.toInt(),
                border = 0xFF2A2A2A.toInt(),
                text = 0xFFFFFFFF.toInt(),
                muted = 0xFF8A8A8A.toInt()
            )
        } else {
            Palette(
                bg = 0xFFF2F2F2.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                border = 0xFFDDDDDD.toInt(),
                text = 0xFF000000.toInt(),
                muted = 0xFF6E6E6E.toInt()
            )
        }

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()

    fun text(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(
                Typeface.MONOSPACE,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    fun rounded(fill: Int, stroke: Int, radiusPx: Float, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun circle(fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }

    fun dot(ctx: Context): View =
        View(ctx).apply { background = circle(RED) }

    /** Keeps content clear of the status bar, navigation bar and keyboard. */
    @Suppress("DEPRECATION")
    fun applyInsets(root: View) {
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                l + insets.systemWindowInsetLeft,
                t + insets.systemWindowInsetTop,
                r + insets.systemWindowInsetRight,
                b + insets.systemWindowInsetBottom
            )
            insets
        }
    }
}
