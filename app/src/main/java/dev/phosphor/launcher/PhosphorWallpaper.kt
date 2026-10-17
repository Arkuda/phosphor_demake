package dev.phosphor.launcher

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class PhosphorWallpaper:WallpaperService() {
    override fun onCreateEngine():Engine=TerminalEngine()
    inner class TerminalEngine:Engine() {
        private val handler=Handler(Looper.getMainLooper())
        private val prefs=getSharedPreferences("phosphor",MODE_PRIVATE)
        private var visible=false
        private var ready=false
        // Snapshots are consumed by the capped frame loop, not rendered for each sensor callback.
        private val device=DeviceRepository(this@PhosphorWallpaper){}
        private val crtEffect=CrtEffect()
        private val tick=object:Runnable{override fun run(){if(visible&&ready){draw();handler.postDelayed(this,if(!prefs.getBoolean("lockClock",true)&&getSystemService(PowerManager::class.java).isPowerSaveMode)1000L else 500L)}}}
        private val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(visible&&ready)draw();if(android.os.Build.VERSION.SDK_INT>=27&&(key=="theme"||key=="lockTheme"))notifyColorsChanged()}
        override fun onCreate(holder:SurfaceHolder){super.onCreate(holder);prefs.registerOnSharedPreferenceChangeListener(listener)}
        override fun onVisibilityChanged(value:Boolean){visible=value;handler.removeCallbacks(tick);if(value){device.start();handler.post(tick)}else device.stop()}
        override fun onSurfaceCreated(holder:SurfaceHolder){super.onSurfaceCreated(holder);ready=true;if(visible)handler.post(tick)}
        override fun onSurfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){super.onSurfaceChanged(holder,format,width,height);if(visible)draw()}
        override fun onSurfaceDestroyed(holder:SurfaceHolder){ready=false;handler.removeCallbacks(tick);super.onSurfaceDestroyed(holder)}
        override fun onDestroy(){visible=false;device.stop();handler.removeCallbacksAndMessages(null);prefs.unregisterOnSharedPreferenceChangeListener(listener);super.onDestroy()}
        override fun onComputeColors():android.app.WallpaperColors? {
            if(android.os.Build.VERSION.SDK_INT<27)return null
            val choice=prefs.getString("lockTheme","match")
            val ink=Inks.get(if(choice=="match")prefs.getString("theme","phosphor") else choice)
            val sample=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            sample.eraseColor(ink.bg)
            val colors=android.app.WallpaperColors.fromBitmap(sample)
            sample.recycle()
            return colors
        }
        private fun draw(){
            val holder=surfaceHolder
            if(!ready||!visible||!holder.surface.isValid)return
            val canvas=try{holder.lockCanvas()}catch(e:IllegalArgumentException){null}?:return
            try{
                val choice=prefs.getString("lockTheme","match");val ink=Inks.get(if(choice=="match")prefs.getString("theme","phosphor") else choice)
                canvas.drawColor(ink.bg);val scale=canvas.width/540f;canvas.scale(scale,scale);val h=canvas.height/scale
                val u=TerminalUi(canvas,ink,540f,h,SystemClock.uptimeMillis()/1000f)
                // System clock and notifications own the top third; never pretend to replace them.
                val now=LocalDateTime.now();val clockY=h*.44f
                if(prefs.getBoolean("lockClock",true)){
                    u.text("◆ phosphor  ~/lock",28f,clockY-44,19f,ink.dim)
                    u.clock(now.format(DateTimeFormatter.ofPattern(if(prefs.getBoolean("clock24",true))"HH:mm" else "hh:mm")),45f,clockY,450f,133f,blinkColon=true,shadow=true)
                    u.text(now.format(DateTimeFormatter.ofPattern("EEE dd MMM yyyy",Locale.ENGLISH)).lowercase(),143f,clockY+154,20f,ink.fg)
                }
                val rows=ArrayList<String>()
                if(prefs.getBoolean("lockWeather",true)){val w=device.weather;rows.add(if(w!=null){val f=prefs.getString("units","c")=="f";val temp=(if(f)w.temperature*1.8+32 else w.temperature).roundToInt();"wx  $temp°${if(f)"f" else "c"} · ${SkyRenderer.description(w.code)} · ${w.city}"}else "wx  ${device.weatherStatus}")}
                if(prefs.getBoolean("lockBattery",true))rows.add("bat ${if(device.batteryLevel>=0)"${device.batteryLevel}%" else "unavailable"} · ${if(device.charging)"charging" else "on battery"}")
                if(prefs.getBoolean("lockEvent",true))rows.add("cal ${device.events.firstOrNull()?.title?:"no upcoming event"}")
                if(prefs.getBoolean("lockNotifications",true))rows.add("log ${device.notifications.size} notifications")
                val py=clockY+211;val ph=40f+rows.size*34
                if(rows.isNotEmpty()){u.panel("status",25f,py,490f,ph);rows.forEachIndexed{i,s->u.text(s,40f,py+30+i*34,19f,if(i==0)ink.fg else ink.dim,460f)}}
                val bottom=min(h-80,py+ph+56)
                u.text("> unlock to continue${if(now.second%2==0)" █" else ""}",36f,bottom,19f,ink.accent,468f)
                if(prefs.getBoolean("crt",true))crtEffect.draw(u)
            }finally{holder.unlockCanvasAndPost(canvas)}
        }
    }
}
