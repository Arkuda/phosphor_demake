package dev.phosphor.launcher

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import org.json.JSONArray
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class TerminalView(private val host:MainActivity):View(host) {
    var running=false
    var pageIndex=0
    var drawer=false
    var query=""
    var drawerScroll=0f
    private var transientPage:String?=null
    private val tools=Tools(host)
    private var targets:List<TouchTarget> = emptyList()
    private var scale=1f
    private var logicalHeight=1000f
    private var downX=0f;private var downY=0f;private var lastY=0f
    private var downTime=0L
    private var held=false
    private var moving=false
    private var rippleTime=0L
    private var rippleX=0f;private var rippleY=0f
    private val birth=SystemClock.uptimeMillis()
    private var matchingQuery:String?=null
    private var matchingApps:List<AppEntry>?=null
    private var quickSource:String?=null
    private var quickCache:List<String> = emptyList()
    private val rainGlyphs="01ｱｲｳｴｵ:<>".map { it.toString() }
    private var matches:List<AppEntry> = emptyList()
    private val holdAction=Runnable {
        if(!moving){held=true;val target=targets.lastOrNull{it.rect.contains(downX,downY)}
            if(target?.hold!=null)target.hold.invoke() else if(!drawer)host.editPage()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    init { isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Phosphor dashboard" }
    fun close(){tools.close();removeCallbacks(holdAction)}
    fun home(){transientPage=null;pageIndex=0;drawerScroll=0f;invalidate()}
    fun showPage(page:String){transientPage=page;host.drawer(false);invalidate()}
    fun finishPreview(){transientPage=null}
    fun quickNames():List<String> {
        val raw=host.prefs.getString("quick","")?:""
        if(raw!=quickSource) {
            quickSource=raw
            quickCache=try {val a=JSONArray(raw);List(min(7,a.length())){a.getString(it)}}
            catch(e:Exception){listOf("torch","wifi","bluetooth","sound","do not disturb","location","battery saver")}
        }
        return quickCache
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        scale=width/540f;if(scale<=0)return
        logicalHeight=height/scale
        val ink=Inks.get(host.prefs.getString("theme","phosphor"));canvas.drawColor(ink.bg)
        val saved=canvas.save();canvas.scale(scale,scale)
        val t=SystemClock.uptimeMillis()/1000f
        val font=when(host.prefs.getString("textSize","medium")){"small"->.9f;"large"->1.12f;"xl"->1.27f;else->1f}
        val u=TerminalUi(canvas,ink,540f,logicalHeight,t,font)
        ambience(u)
        val pages=host.pages();pageIndex=pageIndex.coerceIn(0,pages.lastIndex)
        val page=if(drawer)"apps" else transientPage?:pages[pageIndex]
        header(u,page)
        host.art.beginFrame()
        if(drawer)drawApps(u,108f,logicalHeight-150f) else {
            val y=43f;val h=logicalHeight-87f
            when(page){
                "dashboard"->dashboard(u,y,h)
                "weather"->{clock(u,y,h*.2f);weather(u,16f,y+h*.22f,508f,h*.76f,true)}
                "inbox"->{clock(u,y,h*.19f);var top=y+h*.21f;val m=host.device.media;if(m!=null){media(u,16f,top,508f,135f);top+=149};log(u,16f,top,508f,y+h-top)}
                "monitor"->{clock(u,y,h*.2f);system(u,16f,y+h*.23f,508f,h*.23f);net(u,16f,y+h*.48f,508f,h*.5f)}
                "zen"->zen(u,y,h)
                "art"->art(u,y,h)
                "agenda"->agenda(u,16f,y,508f,h)
                "media"->media(u,16f,y,508f,h)
                "apps"->{u.text("↑ swipe up to search all apps",27f,y,20f,ink.dim);u.hit("search apps",16f,y,508f,48f){host.drawer(true)};drawApps(u,y+52,h-55)}
                "today"->{clock(u,y,h*.19f);calendar(u,16f,y+h*.22f,508f,h*.34f);agenda(u,16f,y+h*.59f,508f,h*.4f)}
                else->tools.draw(page,u,16f,y,508f,h)
            }
        }
        footer(u)
        glass(u)
        ripple(u)
        val boot=(SystemClock.uptimeMillis()-birth)/650f
        if(boot<1f){u.rect(0f,0f,540f,logicalHeight*(1-boot)/2,ink.bg);u.rect(0f,logicalHeight*(1+boot)/2,540f,logicalHeight*(1-boot)/2,ink.bg);u.line(10f,logicalHeight/2,530f,logicalHeight/2,TerminalUi.withAlpha(ink.bright,((1-boot)*255).toInt()),2f)}
        targets=u.targets
        canvas.restoreToCount(saved)
        host.art.endFrame()
        val description="Phosphor $page · ${pageIndex+1}/${pages.size}"
        if(contentDescription!=description)contentDescription=description
        if(running && isAttachedToWindow)postInvalidateDelayed(if(host.device.saver)250L else 66L)
    }
    private fun header(u:TerminalUi,page:String) {
        val d=host.device
        u.rect(0f,0f,540f,25f,TerminalUi.withAlpha(u.ink.faint,180))
        u.text("◆ phosphor",10f,2f,20f/u.fontScale,u.ink.fg);u.text("~/$page",139f,2f,20f/u.fontScale,u.ink.dim,if(page=="dashboard")205f else 140f)
        if(page!="dashboard")u.text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),286f,2f,18f/u.fontScale,u.ink.fg,66f)
        u.text(d.networkName,359f,2f,18f/u.fontScale,u.ink.dim,62f)
        u.bar(428f,9f,42f,6f,d.batteryLevel/100f,u.ink.fg);u.text(if(d.batteryLevel>=0)"${d.batteryLevel}%" else "—",480f,2f,18f/u.fontScale,u.ink.fg)
        u.hit("Phosphor settings",0f,0f,280f,32f){host.settings()}
        u.hit("system settings",347f,0f,193f,32f){host.open(Intent(Settings.ACTION_SETTINGS))}
    }
    private fun clock(u:TerminalUi,y:Float,h:Float) {
        val now=LocalDateTime.now();val is24=host.prefs.getBoolean("clock24",true)
        val value=now.format(DateTimeFormatter.ofPattern(if(is24)"HHmm" else "hhmm"))
        val clockH=min(120f,h*.61f);u.clock(value,52f,y+6f,405f,clockH)
        u.text(now.format(DateTimeFormatter.ofPattern("ss")),472f,y+clockH-21,20f,u.ink.accent)
        if(!is24)u.text(if(now.hour<12)"am" else "pm",474f,y+20f,16f,u.ink.dim)
        val date=now.format(DateTimeFormatter.ofPattern("EEE dd MMM yyyy",Locale.ENGLISH)).lowercase()
        val week=now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
        u.text(date,88f,y+clockH+22f,20f/u.fontScale,u.ink.fg)
        u.text("wk $week  day ${now.dayOfYear}",277f,y+clockH+22f,18f/u.fontScale,u.ink.dim,247f)
        val rail=y+h-8;u.line(11f,rail,529f,rail,u.ink.dim,1.3f);u.line(11f,rail,11f+518*now.second/60f,rail,u.ink.accent,1.6f);u.dot(11f+518*now.second/60f,rail,2f,u.ink.bright)
        if(now.second<2){for(i in 0..11){val px=60f+(i*37.3f)%390;val py=y+clockH+((u.time%2)*25+i*3)%40;u.dot(px,py,.9f,TerminalUi.withAlpha(u.ink.dim,90))}}
        u.hit("clock · alarms",16f,y,508f,h){host.open(Intent(AlarmClock.ACTION_SHOW_ALARMS))}
    }
    private fun artScene(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val error=host.art.error
        if(error==null) {
            host.art.place((x*scale).roundToInt(),(y*scale).roundToInt(),(w*scale).roundToInt(),(h*scale).roundToInt())
        } else {
            u.text("art unavailable",x+12,y+max(8f,h*.35f),20f,u.ink.fg,w-24)
            u.text(error,x+12,y+max(38f,h*.35f+30f),15f,u.ink.dim,w-24)
            u.text("[ tap to retry ]",x+12,y+max(65f,h*.35f+58f),17f,u.ink.accent,w-24)
            u.hit("retry ASCII art",x,y,w,h){host.art.retry()}
        }
    }
    private fun art(u:TerminalUi,y:Float,h:Float) {
        val piece=host.art.selected
        val paused=host.prefs.getBoolean("ascii_paused",false)
        val mono=host.prefs.getBoolean("ascii_mono",false)
        u.panel("ascii.rest",16f,y,508f,h,piece.category)
        u.text(piece.name,30f,y+27f,24f,u.ink.bright,478f)
        u.text(piece.note,30f,y+66f,16f,u.ink.dim,478f)
        val top=y+100f;val imageHeight=max(24f,h-240f)
        artScene(u,28f,top,484f,imageHeight)
        if(host.art.error==null)u.hit("choose ASCII artwork",28f,top,484f,imageHeight){host.chooseArt()}
        u.text("[ choose artwork › ]",30f,y+h-122f,20f,u.ink.accent,478f)
        u.hit("choose ASCII artwork",25f,y+h-130f,490f,44f){host.chooseArt()}
        u.text(if(paused)"[ play ▶ ]" else "[ pause Ⅱ ]",30f,y+h-74f,20f,u.ink.fg,214f)
        u.hit(if(paused)"play ASCII animation" else "pause ASCII animation",25f,y+h-82f,222f,44f){host.prefs.edit().putBoolean("ascii_paused",!paused).apply()}
        u.text(if(mono)"[ launcher ink ]" else "[ original colors ]",260f,y+h-74f,18f,u.ink.fg,247f)
        u.hit("ASCII colors · ${if(mono)"launcher ink" else "original palette"}",255f,y+h-82f,260f,44f){host.prefs.edit().putBoolean("ascii_mono",!mono).apply()}
        u.text("offline · @bas3line · MIT",30f,y+h-27f,15f,u.ink.dim,478f)
        u.hit("ASCII art settings",20f,y,500f,27f){host.artSettings()}
    }
    private fun zen(u:TerminalUi,y:Float,h:Float) {
        clock(u,y+h*.12f,h*.24f)
        if(host.prefs.getBoolean("ascii_zen",false)) {
            artScene(u,16f,y+h*.43f,508f,h*.42f)
            if(host.art.error==null)u.hit("choose ASCII artwork",16f,y+h*.43f,508f,h*.42f){host.chooseArt()}
            u.text(host.art.selected.name,26f,y+h*.89f,20f,u.ink.fg,488f)
            u.text("[ ascii.rest · art settings › ]",26f,y+h*.95f,17f,u.ink.accent,488f)
            u.hit("ASCII art settings",16f,y+h*.88f,508f,h*.11f){host.artSettings()}
        } else {
            SkyRenderer.draw(u,16f,y+h*.45f,508f,h*.42f,host.device.weather)
            val weather=host.device.weather
            u.text(if(weather!=null)"${temp(weather.temperature)}° · ${SkyRenderer.description(weather.code)} · ${weather.city}" else "weather not configured",26f,y+h*.91f,18f,u.ink.dim,488f)
            u.hit("configure weather",16f,y+h*.43f,508f,h*.51f){host.weatherSettings()}
            u.text("[ use ascii.rest artwork › ]",26f,y+h*.96f,17f,u.ink.accent,488f)
            u.hit("use ASCII artwork in Zen",16f,y+h*.95f,508f,h*.04f){host.prefs.edit().putBoolean("ascii_zen",true).apply();host.chooseArt()}
        }
    }
    private fun dashboard(u:TerminalUi,y:Float,h:Float) {
        if(h<850f || u.fontScale>1.05f) {
            clock(u,y,h*.23f)
            val wy=y+h*.23f+14f
            weather(u,16f,wy,508f,h*.44f,false)
            var ly=wy+h*.44f+14f
            if(host.device.media!=null && h>850f){media(u,16f,ly,508f,136f);ly+=150f}
            log(u,16f,ly,508f,max(60f,y+h-ly))
            return
        }
        val clockH=h*.195f;clock(u,y,clockH)
        val sysY=y+clockH+15;val sysH=h*.16f
        system(u,16f,sysY,248f,sysH);net(u,275f,sysY,248f,sysH)
        val wxY=sysY+sysH+16;val wxH=h*.285f
        weather(u,16f,wxY,508f,wxH,false)
        val rowY=wxY+wxH+16;val rowH=h*.203f
        if(host.device.media==null){quick(u,16f,rowY,248f,rowH);calendar(u,275f,rowY,248f,rowH)}
        val logY=rowY+rowH+15
        if(host.device.media!=null) {
            val mediaHeight=min(130f,h*.14f)
            media(u,16f,rowY,508f,mediaHeight)
            log(u,16f,rowY+mediaHeight+14f,508f,max(56f,y+h-rowY-mediaHeight-14f))
        } else log(u,16f,logY,508f,max(56f,y+h-logY))
    }
    private fun system(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val d=host.device;u.panel("sys",x,y,w,h)
        val gap=min(26f,(h-28)/5);val bw=w*.54f
        val rows=listOf(Triple("bat",d.batteryLevel/100f,if(d.batteryLevel>=0)"${d.batteryLevel}%" else "—"),Triple("mem",if(d.memoryTotal>0)d.memoryUsed.toFloat()/d.memoryTotal else 0f,if(d.memoryTotal>0)"${d.memoryUsed*100/d.memoryTotal}%" else "—"),Triple("dsk",if(d.storageTotal>0)d.storageUsed.toFloat()/d.storageTotal else 0f,if(d.storageTotal>0)"${d.storageUsed*100/d.storageTotal}%" else "—"))
        rows.forEachIndexed { i,r->val top=y+25+i*gap;u.text(r.first,x+7,top,18f,u.ink.dim);u.bar(x+51,top+7,bw,7f,r.second);u.text(r.third,x+w-55,top,18f,u.ink.fg,52f)}
        val ty=y+25+3*gap
        val temperature=if(d.batteryTemp.isFinite())String.format(Locale.US,"%.1f°c",d.batteryTemp) else "—"
        val voltage=if(d.batteryVoltage.isFinite())String.format(Locale.US,"%.2fv",d.batteryVoltage) else "—"
        u.text("tmp $temperature",x+7,ty,18f,u.ink.dim,w*.64f);u.text(voltage,x+w-65,ty,18f,u.ink.dim)
        val up=SystemClock.elapsedRealtime()/1000;u.text("up  ${up/3600}h ${up/60%60}m ${up%60}s",x+7,ty+gap,18f,u.ink.dim,w-14)
        u.hit("system gauges",x,y,w,h){host.open(Intent(Settings.ACTION_SETTINGS))}
    }
    private fun speed(n:Long):String=when{n<0->"—";n>=1024*1024->String.format(Locale.US,"%.1fM/s",n/1048576.0);n>=1024->"${n/1024}K/s";else->"${n}B/s"}
    private fun net(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val d=host.device;u.panel("net",x,y,w,h,d.networkName)
        u.text("↓ ${speed(d.rxSpeed)}",x+6,y+25,18f,u.ink.fg,w*.52f);u.text("↑${speed(d.txSpeed)}",x+w*.60f,y+25,18f,u.ink.fg,w*.39f)
        val history=d.networkHistory;val peak=history.maxOfOrNull{max(it.first,it.second)}?:0L
        val gy=y+55;val gh=max(15f,h-85);val steps=max(1,history.size-1)
        u.line(x+9,gy+gh,x+w-9,gy+gh,u.ink.faint)
        history.forEachIndexed{i,p->val px=x+9+(w-18)*i/steps;val ry=(p.first.toFloat()/max(1L,peak))*gh;val ty=(p.second.toFloat()/max(1L,peak))*gh
            for(k in 0..(ry/4).toInt())u.dot(px,gy+gh-k*4,1f,u.ink.accent)
            if(ty>1)u.dot(px+1.5f,gy+gh-ty,1f,u.ink.fg)
        }
        u.text("peak ${speed(peak)}",x+6,y+h-24,18f,u.ink.dim,w-12)
        u.hit("internet panel",x,y,w,h){host.quick("internet")}
    }
    private fun temp(n:Double):Int=(if(host.prefs.getString("units","c")=="f")n*1.8+32 else n).roundToInt()
    private fun weather(u:TerminalUi,x:Float,y:Float,w:Float,h:Float,large:Boolean) {
        val weather=host.device.weather
        u.panel("wx",x,y,w,h,weather?.city?:"location off")
        if(weather==null){
            SkyRenderer.draw(u,x+8,y+47,w-16,h-95,null)
            u.rect(x+9,y+26,w-18,46f,u.ink.bg)
            u.text("weather not configured",x+16,y+29,20f,u.ink.fg,w-32)
            u.text(host.device.weatherStatus,x+16,y+h-48,16f,u.ink.dim,w-32)
            u.text("[ choose city / allow location ]",x+16,y+h-27,17f,u.ink.accent,w-32)
            u.hit("configure weather",x,y,w,h){host.weatherSettings()};return
        }
        val infoH=if(large)120f else 100f
        SkyRenderer.draw(u,x+8,y+28,w-16,h-83,weather)
        u.rect(x+9,y+24,min(185f,w*.42f),infoH,u.ink.bg)
        u.text(SkyRenderer.description(weather.code),x+17,y+26,18f,u.ink.bright,205f)
        u.clock(abs(temp(weather.temperature)).toString(),x+17,y+49,85f,57f,u.ink.bright)
        u.text(if(temp(weather.temperature)<0)"−" else "°${host.prefs.getString("units","c")}",x+109,y+55,20f,u.ink.accent)
        u.text("feels ${temp(weather.feels)}°  ${weather.humidity}%",x+17,y+113,18f,u.ink.fg,220f)
        val imperial=host.prefs.getString("units","c")=="f";val wind=(weather.wind*(if(imperial).621371 else 1.0)).roundToInt()
        u.text("← ${wind}${if(imperial)"mph" else "kmh"}",x+17,y+135,18f,u.ink.dim)
        val graphH=if(large)h*.22f else 40f;val graphY=y+h-graphH-57
        val temps=weather.hourlyTemps.take(24);val low=temps.minOrNull()?:0.0;val high=temps.maxOrNull()?:low+1
        temps.forEachIndexed{i,v->val gx=x+13+i*(w-50)/23;val gy=graphY+graphH-((v-low)/max(1.0,high-low)*graphH).toFloat();u.dot(gx,gy,1.4f,u.ink.fg)
            val rain=weather.hourlyRain.getOrElse(i){0};u.line(gx,graphY+graphH,gx,graphY+graphH-graphH*rain/100f,u.ink.faint,3f)
            if(i%3==0){val hour=weather.hourlyTimes.getOrNull(i)?.substringAfter('T')?.take(2)?:"";u.text(if(i==0)"now" else hour,gx-6,y+h-51,17f,if(i==0)u.ink.accent else u.ink.dim)}
        }
        u.text("${temp(high)}°",x+w-39,graphY-6,16f,u.ink.fg);u.text("${temp(low)}°",x+w-39,graphY+graphH-11,16f,u.ink.dim)
        for(i in 0..2){val date=weather.dailyDates.getOrNull(i);val day=try{LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE",Locale.ENGLISH)).lowercase()}catch(e:Exception){"—"}
            u.text("$day ↑${weather.dailyHigh.getOrNull(i)?.let{temp(it)}?:"—"} ↓${weather.dailyLow.getOrNull(i)?.let{temp(it)}?:"—"}",x+8+i*(w-16)/3,y+h-27,17f,u.ink.fg,(w-16)/3)
        }
        if(System.currentTimeMillis()-weather.updated>3600000)u.text("cached",x+w-85,y+27,15f,u.ink.warn)
        u.hit("weather settings and refresh",x,y,w,h){host.weatherSettings()}
    }
    private fun quick(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val actions=quickNames();val d=host.device;u.panel("quick",x,y,w,h,"edit")
        val gap=min(25f,(h-30)/max(1,actions.size))
        actions.forEachIndexed{i,name->val state=when(name){"torch"->if(d.torch)"on" else "off";"wifi"->if(d.wifi)"on" else "off";"bluetooth"->if(d.bluetooth)"on" else "off";"sound"->when(d.sound){"sound"->"ring";"vibrate"->"vibr";"silent"->"mute";else->"—"};"do not disturb"->if(d.dnd)"on" else "off";"location"->if(d.location)"on" else "off";"battery saver"->if(d.saver)"on" else "off";else->"↗"}
            val yy=y+27+i*gap;val on=state!="off" && state!="mute" && state!="—"
            u.text(if(on)"●" else "○",x+6,yy,16f,if(on)u.ink.accent else u.ink.dim);u.text(name,x+29,yy,18f,if(on)u.ink.fg else u.ink.dim,w-85);u.text(state,x+w-54,yy,18f,if(on)u.ink.accent else u.ink.dim,51f)
            u.hit("$name $state",x+2,yy-2,w-4,gap){host.quick(name)}
        }
        u.hit("edit quick actions",x+w-72,y-3,72f,28f){host.settings()}
    }
    private fun calendar(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val now=LocalDate.now();val month=now.withDayOfMonth(1);val first=month.dayOfWeek.value-1
        u.panel(now.format(DateTimeFormatter.ofPattern("MMMM",Locale.ENGLISH)).lowercase(),x,y,w,h,now.year.toString())
        val cw=(w-12)/7;val rh=min(25f,(h-50)/6);val ty=y+30
        for(i in 0..6)u.text(listOf("mo","tu","we","th","fr","sa","su")[i],x+6+i*cw,ty,17f,u.ink.dim,cw)
        for(day in 1..month.lengthOfMonth()){val idx=first+day-1;val px=x+6+idx%7*cw;val py=ty+25+idx/7*rh
            if(day==now.dayOfMonth)u.rect(px-1,py-1,24f,21f,u.ink.accent)
            val busy=host.device.events.any{Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate()==month.withDayOfMonth(day)}
            u.text(day.toString().padStart(2,' '),px,py,17f,if(day==now.dayOfMonth)u.ink.bg else if(idx%7>=5)u.ink.dim else u.ink.fg)
            if(busy)u.dot(px+23,py+16,1.5f,u.ink.accent)
        }
        u.hit("open calendar",x,y,w,h){host.open(Intent(Intent.ACTION_VIEW,Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))}
    }
    private fun log(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val notices=host.device.notifications
        u.panel("notifications ${notices.size}",x,y,w,h,"agenda")
        u.hit("open agenda page",x+w-113,y-2,113f,28f){val p=host.pages();val i=p.indexOf("agenda");if(i>=0)pageIndex=i else host.open(Intent(Intent.ACTION_VIEW,Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")));invalidate()}
        val lines=max(0,((h-33)/24).toInt())
        if(notices.isEmpty()){
            val enabled=Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")?.contains(context.packageName)==true
            if(!enabled){u.text("notification access is off",x+9,y+29,18f,u.ink.dim,w-18);if(h>74)u.text("[ enable notifications + media ]",x+9,y+54,17f,u.ink.accent,w-18);u.hit("enable notification access",x,y+23,w,h-23){host.open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}}
            else {u.text("no notifications · all quiet",x+9,y+29,18f,u.ink.dim,w-18);if(h>90)scope(u,x+10,y+65,w-20,h-75)}
        }else for(i in 0 until min(lines,notices.size)){
            val n=notices[i];val yy=y+28+i*24;val hour=Instant.ofEpochMilli(n.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            val message=if(n.title.isNotEmpty())"${n.title}: ${n.text}" else n.text
            val typed=((System.currentTimeMillis()-n.time).coerceAtLeast(0)/22).coerceAtMost(message.length.toLong()).toInt()
            u.text(hour,x+7,yy,17f,u.ink.dim);u.text(n.app.lowercase(),x+73,yy,17f,u.ink.accent,113f);u.text(message.take(typed),x+197,yy,17f,u.ink.fg,w-204)
            u.hit("${n.app}: ${n.title} ${n.text}",x+3,yy-2,w-6,24f,hold={host.choose(n.app,listOf("open","dismiss")){if(it==0)openNotice(n) else host.device.dismissNotice(n.key)}}){openNotice(n)}
        }
    }
    private fun openNotice(n:Notice){if(!NotificationBridge.open(n.key))host.toast("This notification has no available action")}
    private fun scope(u:TerminalUi,x:Float,y:Float,w:Float,h:Float){var previous=y+h/2;for(i in 0..(w/3).toInt()){val xx=x+i*3;val v=y+h/2+sin(i*.12f+u.time)*h*.18f*sin(i*.03f-u.time*.4f);if(i>0)u.line(xx-3,previous,xx,v,u.ink.dim);previous=v}}
    private fun agenda(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        u.panel("agenda",x,y,w,h,LocalDate.now().toString());val events=host.device.events
        if(context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)!=android.content.pm.PackageManager.PERMISSION_GRANTED){u.text("calendar access is off",x+16,y+42,20f,u.ink.dim);u.button("[ allow calendar ]",x+16,y+87,w-32){host.permission(android.Manifest.permission.READ_CALENDAR){host.device.refreshCalendar()}};return}
        if(events.isEmpty()){u.text("no upcoming events",x+16,y+44,20f,u.ink.dim);return}
        events.take(((h-40)/68).toInt().coerceAtLeast(0)).forEachIndexed{i,e->val yy=y+35+i*68;u.text(Instant.ofEpochMilli(e.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE dd MMM · HH:mm",Locale.ENGLISH)).lowercase(),x+14,yy,18f,u.ink.accent,w-28);u.text(e.title,x+14,yy+25,21f,u.ink.fg,w-28);u.line(x+14,yy+55,x+w-14,yy+55,u.ink.faint);u.hit("event ${e.title}",x+4,yy-4,w-8,64f){host.open(Intent(Intent.ACTION_VIEW,Uri.parse("content://com.android.calendar/events/${e.id}")))}}
    }
    private fun media(u:TerminalUi,x:Float,y:Float,w:Float,h:Float) {
        val media=host.device.media;u.panel("media",x,y,w,h,if(media?.playing==true)"playing" else "paused")
        if(media==null){u.text("nothing playing",x+17,y+43,22f,u.ink.dim);u.text("enable notification access for media",x+17,y+79,17f,u.ink.dim,w-34);u.hit("media notification access",x,y,w,h){host.open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))};return}
        val large=h>300;val center=y+if(large)h*.4f else 31f
        if(large)for(i in 0..20){val level=if(media.playing)(.3f+.7f*abs(sin(i*.9f+u.time*(i%3+2)))) else .08f;for(j in 0..(level*25).toInt())u.rect(x+21+i*(w-42)/21,y+h*.34f-j*5,8f,3f,if(j>19)u.ink.fg else u.ink.accent)}
        u.text(media.title,x+17,center,if(large)25f else 20f,u.ink.bright,w-34);u.text(media.artist,x+17,center+30,18f,u.ink.dim,w-34)
        val barY=if(large)y+h*.64f else y+h-46
        u.bar(x+17,barY,w-34,4f,if(media.duration>0)media.position.toFloat()/media.duration else 0f)
        val by=if(large)y+h*.75f else y+h-34
        val labels=listOf("|◀",if(media.playing)"Ⅱ" else "▶","▶|");val actions=listOf("previous","toggle","next")
        labels.forEachIndexed{i,s->val bx=x+w*(.16f+i*.27f);u.text("[ $s ]",bx,by,22f,u.ink.fg);u.hit("media ${actions[i]}",bx-10,by-8,w*.24f,44f){host.device.mediaAction(actions[i])}}
    }
    private fun footer(u:TerminalUi) {
        val y=logicalHeight-31;val pins=host.prefs.getStringSet("pins",emptySet())?:emptySet()
        val apps=host.device.apps
        val dock=ArrayList<AppEntry>()
        pins.forEach{key->apps.firstOrNull{it.component.flattenToString()==key}?.let{dock.add(it)}}
        val defaults=listOf("phone","messages","chrome","camera","maps","browser","music","files","settings")
        for(name in defaults){if(dock.size>=5)break;val app=apps.firstOrNull{it.label.lowercase().contains(name) && it !in dock};if(app!=null)dock.add(app)}
        for(app in apps){if(dock.size>=5)break;if(app !in dock)dock.add(app)}
        if(dock.isEmpty()){u.text("↑ apps",235f,y,20f,u.ink.accent);u.hit("open app drawer",0f,y-5,540f,35f){host.drawer(true)}}
        else dock.forEachIndexed{i,app->val cell=508f/dock.size;val x=16+i*cell;u.text(app.label.lowercase(),x+5,y,18f,u.ink.fg,cell-10);u.hit("open ${app.label}",x,y-5,cell,34f,hold={host.appMenu(app)}){host.drawer(false);host.device.launch(app)}}
        val p=host.pages();val total=p.size;for(i in 0 until total)u.dot(270f+(i-(total-1)/2f)*12,logicalHeight-3,if(i==pageIndex)2f else 1.1f,if(i==pageIndex)u.ink.accent else u.ink.dim)
    }
    private fun appMatches():List<AppEntry> {
        val apps=host.device.apps
        if(matchingQuery!=query || matchingApps!==apps){matchingQuery=query;matchingApps=apps;val q=query.trim().lowercase();matches=if(q.isEmpty())apps else apps.mapNotNull{a->val s=a.label.lowercase();val score=when{ s.startsWith(q)->0; s.contains(q)->10;isSubsequence(q,s)->20;editDistance(q,s.take(max(q.length,min(s.length,q.length+1))))<=if(q.length<4)1 else 2->30;else->null };score?.let{Triple(it,a.label,a)}}.sortedWith(compareBy<Triple<Int,String,AppEntry>>{it.first}.thenBy{it.second}).map{it.third}}
        return matches
    }
    private fun isSubsequence(q:String,s:String):Boolean{var i=0;for(c in s)if(i<q.length&&q[i]==c)i++;return i==q.length}
    private fun editDistance(a:String,b:String):Int{var prev=IntArray(b.length+1){it};for(i in 1..a.length){val row=IntArray(b.length+1);row[0]=i;for(j in 1..b.length)row[j]=minOf(row[j-1]+1,prev[j]+1,prev[j-1]+if(a[i-1]==b[j-1])0 else 1);prev=row};return prev[b.length]}
    private fun drawApps(u:TerminalUi,y:Float,h:Float) {
        val list=appMatches();val rowH=49f*u.fontScale
        val maxScroll=max(0f,list.size*rowH-h);drawerScroll=drawerScroll.coerceIn(0f,maxScroll)
        val saved=u.canvas.save();u.canvas.clipRect(16f,y,524f,y+h)
        if(list.isEmpty()){u.text("no matching apps",29f,y+20,20f,u.ink.dim);u.button("[ search the web ]",26f,y+63,488f){webSearch()}}
        else list.forEachIndexed {i,app->val yy=y+i*rowH-drawerScroll;if(yy+rowH>=y&&yy<y+h){if(i%2==0)u.rect(16f,yy,508f,rowH,u.ink.faint);u.text(if(i==0&&query.isNotBlank())"▶" else "·",26f,yy+13,20f,u.ink.accent);u.text(app.label.lowercase(),56f,yy+13,21f,u.ink.fg,440f);if(yy>=y&&yy+rowH<=y+h)u.hit("app ${app.label}",16f,yy,508f,rowH,hold={host.appMenu(app)}){host.drawer(false);host.device.launch(app)}}}
        u.canvas.restoreToCount(saved)
        u.text("${list.size} apps",400f,30f,15f,u.ink.dim,124f)
    }
    fun launchFirst(){val first=appMatches().firstOrNull();if(first!=null){host.drawer(false);host.device.launch(first)} else webSearch()}
    private fun webSearch(){val q=query.trim();if(q.isEmpty())return;host.drawer(false);host.open(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")))}
    private fun ambience(u:TerminalUi) {
        val ambience=host.prefs.getString("ambience","rain");if(ambience=="off")return
        if(ambience=="rain")for(i in 0..34){val x=(i*137.7f)%540;val top=(u.time*(16+i%7*6)+i*79.3f)%(logicalHeight+120)-120;for(j in 0..5){val ch=rainGlyphs[(i+j*3)%rainGlyphs.size];u.text(ch,x,top+j*19,14f,TerminalUi.withAlpha(u.ink.dim,20+j*6))}}
        else for(i in 0..65){val x=(i*137.17f+if(ambience=="snow")sin(u.time+i)*12 else 0f).mod(540f);val y=when(ambience){"embers"->logicalHeight-((i*73.1f+u.time*(10+i%6*3))%logicalHeight);"snow"->(i*73.1f+u.time*(12+i%5*3))%logicalHeight;else->i*97.7f%logicalHeight};u.dot(x,y,if(ambience=="snow")1.6f else 1f,TerminalUi.withAlpha(u.ink.dim,if(ambience=="stars")(55+45*sin(u.time+i)).toInt() else 70))}
    }
    private fun glass(u:TerminalUi) {
        if(!host.prefs.getBoolean("crt",true))return
        if(!u.ink.print){var y=0f;while(y<logicalHeight){u.rect(0f,y,540f,.65f,0x27000000);y+=2.2f};u.rect(0f,0f,2f,logicalHeight,TerminalUi.withAlpha(u.ink.accent,30));u.rect(538f,0f,2f,logicalHeight,TerminalUi.withAlpha(u.ink.accent,30))}
        else for(i in 0..800)u.dot((i*83.17f)%540,(i*67.31f)%logicalHeight,.45f,TerminalUi.withAlpha(u.ink.dim,28))
    }
    private fun ripple(u:TerminalUi){val elapsed=(SystemClock.uptimeMillis()-rippleTime)/1000f;if(elapsed in 0f..0.6f){for(i in 0..47){val a=i*PI/24;u.dot(rippleX+cos(a).toFloat()*elapsed*150,rippleY+sin(a).toFloat()*elapsed*150,.9f,TerminalUi.withAlpha(u.ink.accent,((1-elapsed/.6f)*110).toInt()))}}}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val x=e.x/scale;val y=e.y/scale
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=x;downY=y;lastY=y;downTime=SystemClock.uptimeMillis();held=false;moving=false;rippleX=x;rippleY=y;rippleTime=downTime;postDelayed(holdAction,ViewConfiguration.getLongPressTimeout().toLong());return true}
            MotionEvent.ACTION_MOVE->{val dx=x-downX;val dy=y-downY;if(abs(dx)>15||abs(dy)>15){moving=true;removeCallbacks(holdAction)};if((drawer||host.pages().getOrNull(pageIndex)=="apps")&&abs(dy)>10){drawerScroll+=(lastY-y);invalidate()};lastY=y;return true}
            MotionEvent.ACTION_UP->{removeCallbacks(holdAction);if(held)return true;val dx=x-downX;val dy=y-downY
                if(abs(dx)>70&&abs(dx)>abs(dy)*1.3f&&!drawer){transientPage=null;val count=host.pages().size;pageIndex=(pageIndex+(if(dx<0)1 else -1)+count)%count;drawerScroll=0f;performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);invalidate()}
                else if(!drawer && host.pages().getOrNull(pageIndex)!="apps" && abs(dy)>70 && abs(dy)>abs(dx)){if(dy<0)host.drawer(true) else host.quick("notifications")}
                else if(!moving){targets.lastOrNull{it.rect.contains(x,y)}?.click?.invoke();performClick()}
                return true
            }
            MotionEvent.ACTION_CANCEL->{removeCallbacks(holdAction);return true}
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun getAccessibilityNodeProvider():AccessibilityNodeProvider = object:AccessibilityNodeProvider(){
        override fun createAccessibilityNodeInfo(id:Int):AccessibilityNodeInfo? {
            if(id==View.NO_ID){val n=AccessibilityNodeInfo.obtain(this@TerminalView);onInitializeAccessibilityNodeInfo(n);for(i in targets.indices)n.addChild(this@TerminalView,i+1);return n}
            val target=targets.getOrNull(id-1)?:return null
            return AccessibilityNodeInfo.obtain().apply { setSource(this@TerminalView,id);setParent(this@TerminalView);packageName=context.packageName;className="android.widget.Button";text=target.label;contentDescription=target.label;isClickable=true;isFocusable=true;isVisibleToUser=true;isEnabled=true;addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);if(target.hold!=null)addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);val loc=IntArray(2);getLocationOnScreen(loc);setBoundsInParent(Rect((target.rect.left*scale).toInt(),(target.rect.top*scale).toInt(),(target.rect.right*scale).toInt(),(target.rect.bottom*scale).toInt()));setBoundsInScreen(Rect(loc[0]+(target.rect.left*scale).toInt(),loc[1]+(target.rect.top*scale).toInt(),loc[0]+(target.rect.right*scale).toInt(),loc[1]+(target.rect.bottom*scale).toInt())) }
        }
        override fun performAction(id:Int,action:Int,args:Bundle?):Boolean { val target=targets.getOrNull(id-1)?:return false;return when(action){AccessibilityNodeInfo.ACTION_CLICK->{target.click();sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);true};AccessibilityNodeInfo.ACTION_LONG_CLICK->{target.hold?.invoke();true};AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS->{sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);true};else->false} }
    }
}
