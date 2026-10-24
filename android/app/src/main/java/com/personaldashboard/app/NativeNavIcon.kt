package com.personaldashboard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Native line glyphs for core chat and individually registered plugin entries. */
class NativeNavIcon(context: Context, private val key: String, private val active: Boolean) :
    View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.rgb(33, 33, 33) else Color.rgb(114, 114, 114)
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val glyphs =
        when (key) {
            "harness" ->
                arrayOf(
                    roundRect(3f, 3f, 21f, 18f, 5f),
                    line(6f, 18f, 4f, 21f, 11f, 18f),
                    line(8f, 10f, 16f, 10f),
                )
            "settings" -> {
                val shapes = mutableListOf(circle(12f, 12f, 6f), circle(12f, 12f, 2f))
                val spoke =
                    Path().apply {
                        moveTo(12f, 3f)
                        lineTo(12f, 6f)
                    }
                for (i in 0..7) shapes.add(
                    Path(spoke).apply {
                        transform(Matrix().apply { setRotate(i * 45f, 12f, 12f) })
                    },
                )
                shapes.toTypedArray()
            }
            else ->
                arrayOf(
                    roundRect(4f, 4f, 20f, 20f, 4f),
                    line(8f, 12f, 16f, 12f),
                    line(12f, 8f, 12f, 16f),
                )
        }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save()
        c.scale(width / 24f, height / 24f)
        var i = 0
        while (i < glyphs.size) {
            c.drawPath(glyphs[i], paint)
            i++
        }
        c.restore()
    }

    private fun line(vararg v: Float) =
        Path().apply {
            moveTo(v[0], v[1])
            for (i in 2 until v.size step 2) lineTo(v[i], v[i + 1])
        }

    private fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
        Path().apply {
            addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        }

    private fun circle(x: Float, y: Float, radius: Float) =
        Path().apply {
            addCircle(x, y, radius, Path.Direction.CW)
        }
}
