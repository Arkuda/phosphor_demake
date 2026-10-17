package dev.phosphor.launcher

import kotlin.math.*

object SkyRenderer {
    fun draw(u:TerminalUi,x:Float,y:Float,w:Float,h:Float,weather:WeatherState?) {
        val c=u.canvas;val save=c.save();c.clipRect(x,y,x+w,y+h)
        val night=weather?.isDay==false || (weather==null && java.time.LocalTime.now().hour !in 6..18)
        val code=weather?.code?:0;val rainy=code in 51..99;val snowy=code in 71..77 || code in 85..86
        val cloudy=code in 1..3 || rainy || snowy
        val dots=3.4f
        // The landscape is rasterised onto a braille-sized dot lattice, never a bitmap.
        for(ix in 0..(w/dots).toInt()) {
            val px=x+ix*dots
            val mountain=h*.62f+h*.11f*sin(ix*.075f)+h*.08f*sin(ix*.151f+2f)
            val ridge=h*.78f+h*.08f*sin(ix*.053f+1f)
            for(iy in 0..(h/dots).toInt()) {
                val py=iy*dots
                val hash=(ix*137+iy*97+ix*iy*17).mod(101)
                if(py>mountain && py<ridge && hash<31)u.dot(px,y+py,1.0f,u.ink.dim)
                if(py>=ridge && hash<12)u.dot(px,y+py,1.1f,u.ink.fg)
            }
        }
        // Evergreen silhouettes in the foreground.
        for(i in 0..10){
            val tx=x+w*(.16f+i*.081f);val base=y+h*(.85f+.08f*sin(i*2.2f));val th=h*(.18f+.13f*(.5f+.5f*sin(i*3.7f)))
            for(r in 0..(th/3.8f).toInt()) {
                val spread=r*1.1f
                for(col in -r..r) if((col+r)%2==0 && abs(col*2.4f)<=spread && (r*17+col*13+i*7).mod(7)!=0)u.dot(tx+col*2.4f,base-th+r*3.8f,1.15f,if(i%3==0)u.ink.fg else u.ink.dim)
            }
        }
        if(night){
            for(i in 0..30){val sx=x+(i*71.31f)%w;val sy=y+(i*37.7f)%(h*.5f);u.dot(sx,sy,if(i%7==0)1.5f else .85f,if(sin(u.time*.8f+i)>.2)u.ink.fg else u.ink.dim)}
            val phase=((System.currentTimeMillis()/86400000.0-10957.76)/29.530588853).let{it-floor(it)}
            val mx=x+w*.79f;val my=y+h*.22f;val radius=min(18f,h*.12f)
            for(dx in -9..9)for(dy in -9..9){val nx=dx/9.0;val ny=dy/9.0;if(nx*nx+ny*ny<=1 && (if(phase<.5)nx>cos(phase*2*PI) else nx< -cos(phase*2*PI)))u.dot(mx+dx*radius/9,my+dy*radius/9,1.2f,u.ink.bright)}
        }else{
            val sx=x+w*.73f;val sy=y+h*.23f;val r=min(20f,h*.12f)
            for(a in 0..47){val ang=a*PI/24;u.dot(sx+cos(ang).toFloat()*r,sy+sin(ang).toFloat()*r,1.1f,u.ink.bright)}
            for(i in 0..7){val a=i*PI/4+u.time*.06;for(k in 0..3){val rr=r+6+k*3;u.dot(sx+cos(a).toFloat()*rr,sy+sin(a).toFloat()*rr,1f,u.ink.accent)}}
        }
        if(cloudy)for(i in 0..3){
            val drift=u.time*(weather?.wind?.toFloat()?:4f)*.12f
            val cx=x+((i*w*.29f+drift)%(w+90))-45
            val cy=y+h*(.12f+i%2*.14f)
            for(a in 0..28)for(b in 0..7){val dx=(a-14)*3.3f;val dy=(b-4)*3.3f;if(dx*dx/2400+dy*dy/170<1 && (a+b+i)%3!=0)u.dot(cx+dx,cy+dy,1.1f,u.ink.dim)}
        }
        if(rainy || snowy) for(i in 0..45){val px=x+(i*53.41f+u.time*(if(snowy)4 else 13))%w;val py=y+(i*31.17f+u.time*(if(snowy)13 else 110))%h
            if(snowy)u.text("*",px,py,10f,u.ink.fg) else {u.dot(px,py,1f,u.ink.accent);u.dot(px-1,py+4,1f,u.ink.dim)} }
        if(code in 95..99 && sin(u.time*1.9)> .993)u.rect(x,y,w,h,TerminalUi.withAlpha(u.ink.bright,60))
        if(code in 45..48)for(i in 0..6)for(j in 0..35)u.dot(x+(j*17+u.time*5+i*21)%w,y+h*(.2f+i*.1f),1f,u.ink.dim)
        c.restoreToCount(save)
    }
    fun description(code:Int)=when(code){0->"clear";1->"mostly clear";2->"partly cloudy";3->"overcast";45,48->"fog";in 51..57->"drizzle";in 61..67->"rain";in 71..77->"snow";in 80..82->"rain showers";85,86->"snow showers";in 95..99->"thunderstorm";else->"weather"}
}
