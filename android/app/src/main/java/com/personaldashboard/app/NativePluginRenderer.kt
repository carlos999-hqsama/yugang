package com.personaldashboard.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Closed native component vocabulary; no HTML, eval or Android class names from plugins. */
class NativePluginRenderer(
    private val context: Context,
    private val openLink: (String) -> Unit,
    private val action: (String, JSONObject, Button) -> Unit,
    private val media: (JSONObject) -> View = { TextView(context).apply { text = "图片暂不可用" } },
    private val pick: (String, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    private val clearImages: (String, (Boolean) -> Unit) -> Unit = { _, cb -> cb(true) },
) {
    private var pendingMedia = false
    private val inputs = linkedMapOf<String, EditText>()
    private val values = linkedMapOf<String, String>()
    private val paints = linkedMapOf<String, () -> Unit>()

    fun values(): JSONObject =
        JSONObject().also { out ->
            values.forEach { (k, v) -> out.put(k, v) }
            inputs.forEach { (k, v) -> out.put(k, v.text.toString()) }
        }

    fun editing(): Boolean = pendingMedia || inputs.values.any { it.hasFocus() }

    fun restore(value: JSONObject) {
        inputs.forEach { (k, v) -> if (value.has(k)) v.setText(value.getString(k)) }
        values.keys.toList().forEach { k ->
            if (value.has(k)) {
                values[k] = value.getString(k)
                paints[k]?.invoke()
            }
        }
    }

    fun render(view: JSONObject): View {
        PluginContract.validateView(view)
        inputs.clear()
        values.clear()
        paints.clear()
        return node(view)
    }

    private fun node(value: JSONObject): View =
        when (value.getString("type")) {
            "column",
            "row",
            "card" ->
                LinearLayout(context).apply {
                    orientation =
                        if (value.getString("type") == "row") LinearLayout.HORIZONTAL
                        else LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    if (value.getString("type") == "card")
                        setBackgroundColor(Color.rgb(244, 244, 244))
                    val children = value.getJSONArray("children")
                    for (i in 0 until children.length()) addView(
                        node(children.getJSONObject(i)),
                        LinearLayout.LayoutParams(
                            if (orientation == LinearLayout.HORIZONTAL) 0 else -1,
                            -2,
                            if (orientation == LinearLayout.HORIZONTAL) 1f else 0f,
                        ),
                    )
                }
            "text",
            "title" ->
                TextView(context).apply {
                    text = value.getString("text")
                    textSize = if (value.getString("type") == "title") 23f else 13.5f
                    setTextColor(Color.rgb(34, 34, 34))
                    setTextIsSelectable(true)
                    setPadding(4, 10, 4, 10)
                    if (value.getString("type") == "title") setTypeface(null, Typeface.BOLD)
                }
            "input" ->
                EditText(context).apply {
                    hint = value.optString("hint")
                    setText(value.optString("value"))
                    textSize = 16f
                    filters = arrayOf(InputFilter.LengthFilter(PluginContract.MAX_VIEW_TEXT_CHARS))
                    contentDescription = value.optString("hint", value.getString("key"))
                    inputs[value.getString("key")] = this
                }
            "image" -> media(value)
            "attachments" ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val key = value.getString("key")
                    values[key] = value.optString("value", "[]")
                    val label = TextView(context)
                    fun paint() {
                        val a = runCatching {
                            org.json.JSONArray(values[key])
                        }
                            .getOrDefault(org.json.JSONArray())
                        label.text =
                            if (a.length() == 0) "未选择图片"
                            else
                                (0 until a.length()).joinToString(" · ") {
                                    a.getJSONObject(it).optString("name", "图片")
                                }
                    }
                    paints[key] = { paint() }
                    addView(label)
                    paint()
                    addView(
                        Button(context).apply {
                            text = "选择图片（最多4张）"
                            isAllCaps = false
                            setOnClickListener {
                                isEnabled = false
                                pendingMedia = true
                                pick(value.getString("threadId")) { selected ->
                                    pendingMedia = false
                                    isEnabled = true
                                    if (selected != null) {
                                        values[key] = selected
                                        paint()
                                    }
                                }
                            }
                        },
                    )
                    addView(
                        Button(context).apply {
                            text = "清空所选图片"
                            isAllCaps = false
                            setOnClickListener {
                                isEnabled = false
                                pendingMedia = true
                                clearImages(value.getString("threadId")) { ok ->
                                    pendingMedia = false
                                    isEnabled = true
                                    if (ok) {
                                        values[key] = "[]"
                                        paint()
                                    }
                                }
                            }
                        },
                    )
                }
            "link" ->
                Button(context).apply {
                    text = value.getString("text")
                    isAllCaps = false
                    setOnClickListener { openLink(PluginContract.linkUrl(value.getString("url"))) }
                }
            "button" ->
                Button(context).apply {
                    text = value.getString("text")
                    isAllCaps = false
                    setOnClickListener {
                        if (pendingMedia) return@setOnClickListener
                        val args = values()
                        val confirmation = value.optString("confirm")
                        if (confirmation.isNotEmpty())
                            android.app.AlertDialog.Builder(context)
                                .setMessage(confirmation)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认") { _, _ ->
                                    action(
                                        value.getString("action"),
                                        args.put("confirmed", true),
                                        this,
                                    )
                                }
                                .show()
                        else action(value.getString("action"), args, this)
                    }
                }
            else -> error("不支持此组件")
        }
}
