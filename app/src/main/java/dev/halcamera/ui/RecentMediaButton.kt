package dev.halcamera.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import dev.halcamera.R

/** Shows the latest saved item; remains an accessible album button when the album is empty. */
class RecentMediaButton(context: Context, action: () -> Unit) : Button(context) {
    private val placeholder = ContextCompat.getDrawable(context, R.drawable.ic_gallery)
    private var thumbnail: Drawable? = null
    private var video = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Look.cameraOutline
        style = Paint.Style.STROKE
        strokeWidth = Look.dp(context, 1).toFloat()
    }
    // The gallery grid's video badge is a white mark on a translucent black plate; the same two paints keep the
    // mark readable whichever way the thumbnail falls, since neither a bright nor a dark frame can swallow both.
    private val badgePlate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 0, 0, 0) }
    private val badgeMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.onDark }
    private val playMark = Path()

    init {
        text = ""
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        backgroundTintList = null
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.cameraSurface) }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = circle
        foreground = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask)
        setThumbnail(null, false)
        setOnClickListener { action() }
    }

    fun setThumbnail(bitmap: Bitmap?, video: Boolean) {
        thumbnail = bitmap?.let { RoundedBitmapDrawableFactory.create(resources, it).apply { isCircular = true } }
        this.video = video
        contentDescription = if (bitmap == null) "HALCamera 갤러리 열기"
            else "최근 ${if (video) "동영상" else "사진"} 미리보기, HALCamera 갤러리 열기"
        tooltipText = contentDescription
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = thumbnail ?: placeholder ?: return
        val size = if (thumbnail != null) minOf(width, height) else Look.dp(context, 24)
        val left = (width - size) / 2
        val top = (height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
        if (thumbnail != null && video) drawPlayMark(canvas)
        // A dark thumbnail must still read as an album button over a dark preview.
        canvas.drawCircle(width / 2f, height / 2f, (minOf(width, height) - outline.strokeWidth) / 2f, outline)
    }

    /**
     * The same claim the gallery grid makes with "▶ 0:53": this item is a video. The duration is dropped because
     * a 48dp circle has no room for it, and the mark sits in the middle rather than in a corner because the
     * corners of a circular button are clipped away.
     */
    private fun drawPlayMark(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, Look.dp(context, 9).toFloat(), badgePlate)
        val half = Look.dp(context, 5).toFloat()
        val nose = Look.dp(context, 5).toFloat()
        // The flat edge sits closer to the centre than the tip, so the triangle looks centred rather than measured.
        val tail = Look.dp(context, 4).toFloat()
        playMark.reset()
        playMark.moveTo(cx - tail, cy - half)
        playMark.lineTo(cx - tail, cy + half)
        playMark.lineTo(cx + nose, cy)
        playMark.close()
        canvas.drawPath(playMark, badgeMark)
    }
}
