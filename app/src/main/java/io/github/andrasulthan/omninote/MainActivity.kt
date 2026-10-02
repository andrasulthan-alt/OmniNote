package io.github.andrasulthan.omninote

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "OMNINOTE"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.15f
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D71921"))
            }
        }

        titleRow.addView(title)
        titleRow.addView(
            dot,
            LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
            }
        )

        val subtitle = TextView(this).apply {
            setText(R.string.hello)
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        root.addView(titleRow)
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(wrap, wrap).apply {
                topMargin = (12 * dp).toInt()
            }
        )

        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }

        setContentView(root)
    }
}
