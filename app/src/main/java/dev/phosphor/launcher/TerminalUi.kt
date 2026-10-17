package dev.phosphor.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.*

data class Ink(val name: String, val bg: Int, val faint: Int, val dim: Int, val fg: Int, val accent: Int, val bright: Int, val warn: Int, val print: Boolean = false)
object Inks {
    private fun ink(n: String, vararg c: String, print: Boolean = false) = Ink(n, Color.parseColor(c[0]),Color.parseColor(c[1]),Color.parseColor(c[2]),Color.parseColor(c[3]),Color.parseColor(c[4]),Color.parseColor(c[5]),Color.parseColor(c[6]),print)
    val all = listOf(
        ink("phosphor","#000000","#0a1a3a","#3a5f9e","#9dbeff","#0068ff","#e8f1ff","#ff5a4e"),
        ink("amber","#000000","#2a1800","#8a5a10","#ffc266","#ff9900","#fff0d6","#ff4040"),
        ink("matrix","#000000","#052a10","#1e7a3a","#7cffa0","#00e05a","#e0ffe8","#ff5050"),
        ink("ice","#000000","#16191e","#5e6672","#c9d1dc","#ffffff","#ffffff","#ff5a4e"),
        ink("crimson","#000000","#2a060a","#8a2030","#ff8f9c","#ff2a44","#ffe4e8","#ffb000"),
        ink("violet","#000000","#170a2e","#5a3d9a","#c7b2ff","#8b5cff","#f1ebff","#ff5a7a"),
        ink("cyan","#000000","#032226","#1d6e78","#8cf3ff","#00d8f0","#e6fdff","#ff6a4e"),
        ink("sakura","#000000","#2a0c1e","#8e3c6a","#ffb3d9","#ff5cb0","#ffeef7","#ffb000"),
        ink("toxic","#000000","#162600","#587a10","#d8ff7a","#b6ff00","#f6ffe0","#ff4d4d"),
        ink("navy","#0b1628","#1b2b47","#6f6553","#dcc09a","#c8955c","#f7ead5","#e8644a"),
        ink("paper","#f3eee2","#e0d8c6","#968b74","#2f2a20","#0068ff","#120f09","#d5381f",print=true),
        ink("chalk","#f6f7f9","#e1e5eb","#8a93a3","#2a303b","#ff5a1f","#0a0d12","#d92d3a",print=true),
        ink("riso","#f5f0e8","#e6ddd8","#8c86a0","#22294a","#ff4f86","#0e1433","#e8590c",print=true),
        ink("forest","#f2ecda","#ddd6be","#87907a","#24402f","#2e6b4a","#10241a","#b8452a",print=true)
    )
    fun get(name: String?) = all.firstOrNull { it.name == name } ?: all[0]
}
data class TouchTarget(val rect: RectF, val label: String, val click: () -> Unit, val hold: (() -> Unit)? = null)

class TerminalUi(val canvas: Canvas, val ink: Ink, val width: Float, val height: Float, val time: Float, val fontScale: Float = 1f) {
    val targets = ArrayList<TouchTarget>()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    fun text(value: String, x: Float, y: Float, size: Float = 20f, color: Int = ink.fg, maxWidth: Float = Float.MAX_VALUE) {
        paint.textSize = size * fontScale
        paint.color = color
        paint.clearShadowLayer()
        val count = paint.breakText(value, true, maxWidth, null)
        val v = if (count < value.length && count > 1) value.substring(0, count - 1) + "…" else value.take(count)
        canvas.drawText(v, x, y + paint.textSize * .8f, paint)
    }
    fun textWidth(value: String, size: Float = 20f): Float { paint.textSize = size * fontScale; return paint.measureText(value) }
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { shape.color=color; shape.style=Paint.Style.FILL; canvas.drawRect(x,y,x+w,y+h,shape) }
    fun line(x1: Float,y1: Float,x2: Float,y2: Float,color: Int=ink.dim,stroke:Float=1f) { shape.color=color;shape.style=Paint.Style.STROKE;shape.strokeWidth=stroke;canvas.drawLine(x1,y1,x2,y2,shape);shape.style=Paint.Style.FILL }
    fun dot(x: Float,y:Float,r:Float=1.2f,color:Int=ink.fg) { shape.color=color;shape.style=Paint.Style.FILL;canvas.drawCircle(x,y,r,shape) }
    fun panel(title: String,x:Float,y:Float,w:Float,h:Float,right:String="") {
        shape.style=Paint.Style.STROKE;shape.color=ink.dim;shape.strokeWidth=.9f
        canvas.drawRoundRect(x,y+9,w+x,y+h,4f,4f,shape);shape.style=Paint.Style.FILL
        val tw=textWidth(title,20f)
        rect(x+18,y,tw+22,23f,ink.bg);text(title,x+28,y-1,20f,ink.accent)
        if(right.isNotEmpty()){ val rw=textWidth(right,18f);rect(x+w-rw-27,y,rw+17,23f,ink.bg);text(right,x+w-rw-20,y,18f,ink.dim) }
    }
    fun hit(label:String,x:Float,y:Float,w:Float,h:Float,hold:(()->Unit)?=null,click:()->Unit) { targets.add(TouchTarget(RectF(x,y,x+w,y+h),label,click,hold)) }
    fun button(label:String,x:Float,y:Float,w:Float,h:Float=40f,click:()->Unit) {
        rect(x,y,w,h,ink.faint);text(label,x+(w-textWidth(label,19f))/2,y+(h-19)/2,19f,ink.fg);hit(label,x,y,w,h,click=click)
    }
    fun bar(x:Float,y:Float,w:Float,h:Float,value:Float,color:Int=ink.accent) {
        rect(x,y,w,h,ink.faint);rect(x,y,w*value.coerceIn(0f,1f),h,color)
        val sheen=(time*.19f%1f)*w
        if(sheen<w*value)rect(x+sheen,y,min(15f,w*value-sheen),h,withAlpha(ink.bright,140))
    }
    fun clock(value:String,x:Float,y:Float,w:Float,h:Float,color:Int=ink.bright,glow:Boolean=true,blinkColon:Boolean=false,shadow:Boolean=false) {
        if(value.isEmpty()||w<=0f||h<=0f)return
        val units=(value.length*4-value.count { it==':' }*2-1).toFloat()
        val offset=if(shadow)min(6f,min(w/units,h/5f)*.22f) else 0f
        val cell=min((w-offset)/units,(h-offset)/5f)
        val ox=x+(w-units*cell-offset)/2
        val colonVisible=!blinkColon||(time*2).toLong()%2L==0L
        // Complete the hard shadow before any foreground blocks, including the colon.
        if(shadow)clockGlyphs(value,ox+offset,y+offset,cell,if(ink.print)ink.dim else ink.accent,false,colonVisible,false)
        clockGlyphs(value,ox,y,cell,color,glow,colonVisible,!shadow)
    }
    private fun clockGlyphs(value:String,x:Float,y:Float,cell:Float,color:Int,glow:Boolean,colonVisible:Boolean,printShadow:Boolean) {
        var dx=x
        for(ch in value){
            if(ch==':') {
                if(colonVisible){
                    block(dx,y+cell,cell,cell,color,glow,printShadow)
                    block(dx,y+3*cell,cell,cell,color,glow,printShadow)
                }
                dx+=2*cell
                continue
            }
            val glyph=DIGITS[ch] ?: DIGITS['0']!!
            for(row in 0..4)for(col in 0..2)if(glyph[row][col]=='1')block(dx+col*cell,y+row*cell,cell,cell,color,glow,printShadow)
            dx+=4*cell
        }
    }
    private fun block(x:Float,y:Float,w:Float,h:Float,color:Int,glow:Boolean,printShadow:Boolean) {
        shape.color=color;shape.style=Paint.Style.FILL
        if(glow&&!ink.print){shape.setShadowLayer(9f,0f,0f,withAlpha(color,150));canvas.drawRect(x,y,x+w,y+h,shape);shape.clearShadowLayer()}
        if(printShadow&&ink.print){shape.color=withAlpha(ink.accent,65);canvas.drawRect(x+4,y+5,x+w+4,y+h+5,shape);shape.color=color}
        canvas.drawRect(x,y,x+w,y+h,shape)
    }
    companion object {
        fun withAlpha(color:Int,alpha:Int)=(color and 0x00ffffff) or (alpha.coerceIn(0,255) shl 24)
        private val DIGITS=mapOf(
            '0' to arrayOf("111","101","101","101","111"),'1' to arrayOf("010","110","010","010","111"),
            '2' to arrayOf("111","001","111","100","111"),'3' to arrayOf("111","001","111","001","111"),
            '4' to arrayOf("101","101","111","001","001"),'5' to arrayOf("111","100","111","001","111"),
            '6' to arrayOf("111","100","111","101","111"),'7' to arrayOf("111","001","001","001","001"),
            '8' to arrayOf("111","101","111","101","111"),'9' to arrayOf("111","101","111","001","111")
        )
    }
}
