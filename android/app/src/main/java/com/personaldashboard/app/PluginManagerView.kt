package com.personaldashboard.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Native plugin management. Account and business settings remain inside plugins. */
internal fun pluginManagerView(
    context: Context,
    plugins: JSONArray,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onRemove: (JSONObject) -> Unit,
    recovery: JSONObject? = null,
): LinearLayout {
    val ink = Color.rgb(32, 32, 35)
    val muted = Color.rgb(122, 122, 129)
    val accent = Color.rgb(32, 32, 35)
    val line = Color.rgb(232, 232, 236)
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 10, border: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border) setStroke(dp(1), line)
        }
    fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        }
    fun action(value: String, primary: Boolean = false, click: () -> Unit) =
        label(value, 14f, if (primary) Color.WHITE else ink, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            background =
                RippleDrawable(
                    ColorStateList.valueOf(Color.argb(25, 32, 32, 35)),
                    shape(if (primary) accent else Color.TRANSPARENT),
                    null,
                )
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(16))
        addView(
            action("‹  设置", click = onBack).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(16), 0)
            },
            LinearLayout.LayoutParams(-2, dp(40)),
        )
        val count = plugins.length()
        val enabled = (0 until count).count { plugins.getJSONObject(it).getBoolean("enabled") }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("手机插件", 24f, bold = true))
        titles.addView(
            label("${count} 个已安装 · ${enabled} 个已启用", 12f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(
            action("＋ 添加插件", true, onAdd),
            LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(12) },
        )
        addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val recoveryState=recovery?.optString("status","ok")?:"ok"
        if(recoveryState!="ok")addView(label(when(recoveryState){
            "recovered"->"插件数据已从保留副本恢复，请核对最近的设置。"
            "unavailable"->"插件数据暂不可用，原文件已保留。请勿卸载或清空应用。"
            else->"部分插件数据需要恢复；其余插件可继续使用。"
        },13f,muted),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
        val list =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(Color.WHITE, 12, true)
                clipToOutline = true
            }
        for (i in 0 until count) {
            val m = plugins.getJSONObject(i)
            val id = m.getString("id")
            val title = m.getString("title")
            if (i > 0)
                list.addView(
                    View(context).apply { setBackgroundColor(line) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        leftMargin = dp(14)
                        rightMargin = dp(14)
                    },
                )
            val row =
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(4), dp(10))
                    minimumHeight = dp(72)
                }
            val icon =
                FrameLayout(context).apply {
                    background = shape(Color.rgb(243, 243, 245))
                    addView(
                        NativeNavIcon(context, id, true),
                        FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
                    )
                }
            row.addView(
                icon,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) },
            )
            val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val titleAndVersion =
                SpannableString("$title   ${m.getString("version")}").apply {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        title.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        RelativeSizeSpan(11f / 14f),
                        title.length,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        ForegroundColorSpan(muted),
                        title.length,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            copy.addView(
                label("", 14f).apply {
                    text = titleAndVersion
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { rightMargin = dp(12) },
            )
            copy.addView(
                label(
                        m.optString("description").ifBlank {
                            if (m.getBoolean("enabled")) "已启用" else "已停用"
                        },
                        12f,
                        muted,
                    )
                    .apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle =
                Switch(context).apply {
                    isChecked = m.getBoolean("enabled")
                    showText = false
                    splitTrack = false
                    contentDescription = "${title}启用状态"
                    thumbTintList = null
                    trackTintList = null
                    switchMinWidth = dp(40)
                    thumbDrawable = shape(Color.WHITE, 12, true).apply { setSize(dp(22), dp(22)) }
                    trackDrawable =
                        StateListDrawable().apply {
                            addState(
                                intArrayOf(android.R.attr.state_checked),
                                shape(accent, 12).apply { setSize(dp(40), dp(24)) },
                            )
                            addState(
                                intArrayOf(),
                                shape(Color.rgb(203, 203, 209), 12).apply {
                                    setSize(dp(40), dp(24))
                                },
                            )
                        }
                    setOnCheckedChangeListener { _, checked -> onEnable(id, checked) }
                }
            row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
            val remove =
                action("删除") { onRemove(m) }
                    .apply {
                        contentDescription = "删除${title}"
                        textSize = 13f
                        setTextColor(Color.rgb(85, 85, 92))
                        setPadding(0, 0, 0, 0)
                    }
            row.addView(remove, LinearLayout.LayoutParams(dp(48), dp(48)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (count == 0)
            list.addView(
                label("还没有插件，点击右上角添加。", 14f, muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(36), dp(16), dp(36))
                },
            )
        addView(list, LinearLayout.LayoutParams(-1, -2))
        addView(
            label("插件的账号和偏好，请进入对应插件设置。", 12f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
        )
    }
}
