package vn.tn.assistant.ui

import android.content.Context
import android.graphics.*
import android.view.View

/** Original two-slot silhouette: white circle, colored outer ring and a bottom nameplate. */
internal class TnFloatingPlanBadgeView(context: Context) : View(context) {
    var plan: TnFloatingAccountPlan? = null
        set(value) { field = value; invalidate(); updateDescription() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.createFromAsset(context.assets,"fonts/barlowsemibold.ttf"); textAlign = Paint.Align.CENTER }
    init { isClickable = true; isFocusable = true; setLayerType(LAYER_TYPE_SOFTWARE,null) }
    private fun updateDescription() {
        contentDescription = when(plan?.effectiveKind()) {
            "admin" -> "Tài khoản Admin"
            "premium" -> "Premium còn ${plan?.days()} ngày"
            else -> "Tìm hiểu TN Premium"
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = plan ?: return
        val kind = value.effectiveKind()
        val color = when(kind) { "admin" -> 0xffa783ed.toInt(); "premium" -> 0xff37c9b4.toInt(); else -> 0xffedb452.toInt() }
        val side = minOf(width.toFloat(),height / 1.15f)
        val cx = width / 2f; val cy = side/2f; val radius = side*.5f
        paint.style=Paint.Style.FILL; paint.color=Color.WHITE
        paint.setShadowLayer(side*.04f,0f,side*.02f,0x66000000);canvas.drawCircle(cx,cy,radius,paint);paint.clearShadowLayer()
        // Original ic_speed_limit_base_premium_feature: viewport 189,
        // outer radius 89.5, inner radius 72.7, vertical gold gradient.
        paint.style=Paint.Style.STROKE;paint.strokeWidth=side*(16.8f/189f)
        val light=when(kind) {"admin"->0xffc6a9ff.toInt();"premium"->0xff96eee0.toInt();else->0xffecc891.toInt()}
        val dark=if(kind=="basic")0xffeaa844.toInt() else color
        paint.shader=LinearGradient(cx,side*6.92f/189f,cx,side*184f/189f,light,dark,Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,side*(81.1f/189f),paint);paint.shader=null
        paint.style=Paint.Style.FILL
        text.color=0xff7f8585.toInt();text.textSize=side*.53f
        if(kind=="admin") {
            val path=Path().apply {moveTo(cx,cy-side*.27f);lineTo(cx+side*.24f,cy-side*.17f);lineTo(cx+side*.20f,cy+side*.13f);quadTo(cx,cy+side*.32f,cx-side*.20f,cy+side*.13f);lineTo(cx-side*.24f,cy-side*.17f);close()}
            paint.style=Paint.Style.STROKE;paint.strokeWidth=side*.035f;paint.color=color;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
            canvas.drawCircle(cx,cy-side*.08f,side*.065f,paint)
            canvas.drawRoundRect(RectF(cx-side*.11f,cy+side*.015f,cx+side*.11f,cy+side*.13f),side*.07f,side*.07f,paint)
        } else {
            val content=if(kind=="premium") value.days().toString() else "?"
            while(text.measureText(content)>side*.64f) text.textSize*=.9f
            canvas.drawText(content,cx,cy-(text.ascent()+text.descent())/2f,text)
        }
        val label=if(kind=="admin") "ADMIN" else "PREMIUM"
        val plate=RectF(cx-side*.41f,side*.83f,cx+side*.41f,side*1.065f)
        paint.color=color;canvas.drawRoundRect(plate,side*.025f,side*.025f,paint)
        paint.style=Paint.Style.STROKE;paint.color=0xff5b5964.toInt();paint.strokeWidth=side*.018f;canvas.drawRoundRect(plate,side*.025f,side*.025f,paint);paint.style=Paint.Style.FILL
        text.color=0xff30343c.toInt();text.textSize=side*.16f
        canvas.drawText(label,cx,plate.centerY()-(text.ascent()+text.descent())/2f,text)
    }
}
