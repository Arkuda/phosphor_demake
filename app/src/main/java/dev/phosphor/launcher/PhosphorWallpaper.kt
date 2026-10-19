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
        private val clock24Format=DateTimeFormatter.ofPattern("HH:mm")
        private val clock12Format=DateTimeFormatter.ofPattern("hh:mm")
        private var dateLocale=L10n.locale
        private var dateFormat=DateTimeFormatter.ofPattern("EEE dd MMM yyyy",dateLocale)
        private val tick=object:Runnable{override fun run(){if(visible&&ready){draw();handler.postDelayed(this,if(!prefs.getBoolean("lockClock",true)&&getSystemService(PowerManager::class.java).isPowerSaveMode)1000L else 500L)}}}
        private val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key=="language")L10n.refresh()
            if(visible&&ready)draw()
            if(android.os.Build.VERSION.SDK_INT>=27&&(key=="theme"||key=="lockTheme"))notifyColorsChanged()
        }
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
                val u=TerminalUi(canvas,ink,540f,h,SystemClock.uptimeMillis()/1000f,frames=prefs.getBoolean("frames",true))
                // System clock and notifications own the top third; never pretend to replace them.
                val now=LocalDateTime.now();val clockY=h*.44f
                if(prefs.getBoolean("lockClock",true)){
                    u.text(L10n.text(R.string.platform_lock_heading),28f,clockY-44,19f,ink.dim)
                    u.clock(now.format(if(prefs.getBoolean("clock24",true))clock24Format else clock12Format),45f,clockY,450f,133f,blinkColon=true,shadow=true)
                    if(dateLocale!=L10n.locale){dateLocale=L10n.locale;dateFormat=DateTimeFormatter.ofPattern("EEE dd MMM yyyy",dateLocale)}
                    u.text(now.format(dateFormat).lowercase(dateLocale),143f,clockY+154,20f,ink.fg)
                }
                val rows=ArrayList<String>()
                if(prefs.getBoolean("lockWeather",true)){
                    val w=device.weather
                    val value=if(w!=null){
                        val f=prefs.getString("units","c")=="f"
                        val temp=(if(f)w.temperature*1.8+32 else w.temperature).roundToInt()
                        L10n.text(R.string.platform_lock_weather_value,temp,L10n.text(if(f)R.string.platform_lock_fahrenheit else R.string.platform_lock_celsius),SkyRenderer.description(w.code),w.city)
                    }else device.weatherStatus
                    rows.add(L10n.text(R.string.platform_lock_weather,value))
                }
                if(prefs.getBoolean("lockBattery",true))rows.add(L10n.text(R.string.platform_lock_battery,if(device.batteryLevel>=0)"${device.batteryLevel}%" else L10n.text(R.string.common_unavailable),L10n.text(if(device.charging)R.string.platform_lock_charging else R.string.platform_lock_on_battery)))
                if(prefs.getBoolean("lockEvent",true))rows.add(L10n.text(R.string.platform_lock_event,device.events.firstOrNull()?.title?:L10n.text(R.string.platform_lock_no_event)))
                if(prefs.getBoolean("lockNotifications",true))rows.add(L10n.text(R.string.platform_lock_notifications,device.notifications.size))
                val py=clockY+211;val ph=40f+rows.size*34
                if(rows.isNotEmpty()){u.panel(L10n.text(R.string.platform_lock_status),25f,py,490f,ph);rows.forEachIndexed{i,s->u.text(s,40f,py+30+i*34,19f,if(i==0)ink.fg else ink.dim,460f)}}
                val bottom=min(h-80,py+ph+56)
                u.text(L10n.text(R.string.platform_lock_unlock)+if(now.second%2==0)" █" else "",36f,bottom,19f,ink.accent,468f)
                if(prefs.getBoolean("crt",true))crtEffect.draw(u)
            }finally{holder.unlockCanvasAndPost(canvas)}
        }
    }
}
