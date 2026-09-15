package com.dsh.phoneagent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small styling helpers for the status screen.
 *
 * The whole app builds its UI in code rather than XML: there is exactly one
 * screen, and keeping it out of resources means the values the control path
 * reads can never drift from what the screen shows. The cost is that rounded
 * corners and card chrome have to be constructed by hand, which is what this
 * file centralises.
 */
object Ui {

    // Palette. Kept in one place so the dark variant later only has to swap these.
    const val BG = 0xFFEEF1F6.toInt()
    const val CARD = Color.WHITE
    const val INK = 0xFF0F172A.toInt()
    const val MUTED = 0xFF667085.toInt()
    const val FAINT = 0xFF98A2B3.toInt()
    const val OK = 0xFF0EA36B.toInt()
    const val OK_SOFT = 0xFFE7F7F0.toInt()
    const val WARN = 0xFFE5484D.toInt()
    const val WARN_SOFT = 0xFFFDECEC.toInt()
    const val BRAND = 0xFF2563EB.toInt()
    const val BRAND_SOFT = 0xFFEAF0FF.toInt()
    const val CODE_BG = 0xFF0F172A.toInt()
    const val CODE_INK = 0xFFF8FAFC.toInt()
    const val CODE_MUTED = 0xFF94A3B8.toInt()

    fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    fun Context.sp(value: Float): Float = value

    fun rounded(color: Int, radiusDp: Int, context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }

    fun circle(color: Int, sizeDp: Int, context: Context): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

    /** A white, softly shadowed card. */
    fun Context.card(radiusDp: Int = 20, paddingDp: Int = 18): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, radiusDp, this@card)
            elevation = dp(2).toFloat()
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        }

    fun Context.text(
        value: String,
        sizeSp: Float,
        color: Int = INK,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        includeFontPadding = false
    }

    /** A section heading: small, muted, letter-spaced. */
    fun Context.sectionTitle(value: String): TextView = text(value, 12f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(6), 0, dp(8))
    }

    /** A pill button. `filled` swaps foreground/background emphasis. */
    fun Context.pillButton(
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        textSize = 13.5f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = rounded(bg, 12, this@pillButton)
        setPadding(0, dp(11), 0, dp(11))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** The small square glyph used in front of capability cards. */
    fun glyph(context: Context, kind: Glyph, tint: Int, soft: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(soft, 16, context)
            layoutParams = LinearLayout.LayoutParams(context.dp(34), context.dp(34))
            addView(
                TextView(context).apply {
                    text = when (kind) {
                        Glyph.ACCESSIBILITY -> "♿"
                        Glyph.SERVER -> "≡"
                        Glyph.EYE -> "◉"
                        Glyph.SWIPE -> "↕"
                        Glyph.SEARCH -> "⌕"
                    }
                    textSize = 17f
                    setTextColor(tint)
                    gravity = Gravity.CENTER
                },
            )
        }

    enum class Glyph { ACCESSIBILITY, SERVER, EYE, SWIPE, SEARCH }

    /** Two-column row used by the capability cards. */
    fun Context.twoColumn(left: View, right: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(6) })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
        }

    /** Equal-width row for the stat tiles. */
    fun Context.statRow(tiles: List<View>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.forEachIndexed { index, tile ->
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) params.leftMargin = dp(6)
                if (index < tiles.size - 1) params.rightMargin = dp(6)
                addView(tile, params)
            }
        }
}
