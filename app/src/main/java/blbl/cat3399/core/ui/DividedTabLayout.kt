package blbl.cat3399.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewGroup
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

/**
 * 长排同质标签(年份/季度等)的弱感知分隔 + 选中强调。
 *
 * 背景:一行里塞了十几个年份时,相邻 tab 之间没有任何视觉分界,当前选中项也只有
 * 一条 2dp 下划线和文字明度差,横向滚动时很难定位"我在哪一年"。
 *
 * 做法 —— 全部走 draw,**不参与测量/布局**:
 * - 相邻 tab 之间画一条低透明度竖线,把年份彼此分开(不占宽度 ⇒ 可见数量/密度不变)
 * - 选中 tab 画一层低透明度强调色圆角块,选中项一眼可见但依然克制
 *
 * 配色从主题取(强调色 / 次要文字色),所以亮暗主题都自适应,不额外引入颜色常量。
 * 只用于年份这类长排标签;首页顶栏(9 个分类)不要用,那里标签少、用不着分隔线。
 */
class DividedTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.tabStyle,
) : UserScaleTabLayout(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipRect = RectF()

    private val dividerColor: Int
    private val chipColor: Int

    private val dividerHalfWidthPx: Float
    private val chipRadiusPx: Float
    private val chipInsetXPx: Float
    private val chipInsetYPx: Float

    init {
        val a =
            context.obtainStyledAttributes(
                intArrayOf(R.attr.blblAccent, R.attr.blblOnPageBackdropSecondary),
            )
        val accent = a.getColor(0, Color.WHITE)
        val secondary = a.getColor(1, Color.LTGRAY)
        a.recycle()

        dividerColor = withAlpha(secondary, DIVIDER_ALPHA)
        chipColor = withAlpha(accent, CHIP_ALPHA)

        dividerHalfWidthPx = dp(1f) / 2f
        chipRadiusPx = dp(6f)
        chipInsetXPx = dp(2f)
        chipInsetYPx = dp(3f)

        // 选中项变化时重画色块(dispatchDraw 只跟着滚动/子视图重绘走,这里主动补一次)
        addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = invalidate()

                override fun onTabUnselected(tab: TabLayout.Tab) = invalidate()

                override fun onTabReselected(tab: TabLayout.Tab) = invalidate()
            },
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val strip = getChildAt(0) as? ViewGroup
        if (strip == null || strip.childCount == 0) {
            super.dispatchDraw(canvas)
            return
        }

        val save = canvas.save()
        // dispatchDraw 的 canvas 尚未应用本视图的滚动量,子视图坐标却是"内容坐标",这里手动对齐
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())

        drawSelectedChip(canvas, strip)
        drawDividers(canvas, strip)

        canvas.restore()
        super.dispatchDraw(canvas)
    }

    /** 选中 tab 上盖一层低透明度强调色圆角块 */
    private fun drawSelectedChip(canvas: Canvas, strip: ViewGroup) {
        val selected = getTabAt(selectedTabPosition)?.view ?: return
        if (selected.width <= 0 || selected.height <= 0) return

        val baseLeft = strip.left + selected.left + selected.translationX
        val baseTop = selected.top + selected.translationY
        val left = baseLeft + chipInsetXPx
        val right = baseLeft + selected.width - chipInsetXPx
        val top = baseTop + chipInsetYPx
        val bottom = baseTop + selected.height - chipInsetYPx
        if (right <= left || bottom <= top) return

        chipRect.set(left, top, right, bottom)
        paint.color = chipColor
        canvas.drawRoundRect(chipRect, chipRadiusPx, chipRadiusPx, paint)
    }

    /** 相邻 tab 之间画低透明度竖线(上下留白,视觉更轻) */
    private fun drawDividers(canvas: Canvas, strip: ViewGroup) {
        paint.color = dividerColor
        for (i in 1 until strip.childCount) {
            val child = strip.getChildAt(i) ?: continue
            if (child.width <= 0 || child.height <= 0) continue

            val x = strip.left + child.left + child.translationX
            val baseTop = child.top + child.translationY
            val top = baseTop + child.height * DIVIDER_INSET_RATIO
            val bottom = baseTop + child.height * (1f - DIVIDER_INSET_RATIO)
            canvas.drawRect(x - dividerHalfWidthPx, top, x + dividerHalfWidthPx, bottom, paint)
        }
    }

    private fun withAlpha(color: Int, alphaRatio: Float): Int {
        val alpha = (Color.alpha(color) * alphaRatio).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** 分隔线透明度(相对次要文字色) */
        const val DIVIDER_ALPHA = 0.22f
        /** 选中色块透明度(相对强调色) */
        const val CHIP_ALPHA = 0.16f
        /** 分隔线上下各留白的比例 */
        const val DIVIDER_INSET_RATIO = 0.30f
    }
}
