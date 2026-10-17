package dev.phosphor.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray

class MainActivity : Activity(), LauncherHost {
    override val activity: Activity get() = this
    override lateinit var prefs: SharedPreferences
    override lateinit var device: DeviceRepository
    private lateinit var terminal: TerminalView
    private lateinit var search: EditText
    private lateinit var frame: FrameLayout
    private lateinit var pageTransition: PageTransition
    internal val isChangingPage: Boolean get() = ::pageTransition.isInitialized && pageTransition.isRunning
    lateinit var art: AsciiArt
        private set
    lateinit var dock: Dock
        private set
    lateinit var favorites: Favorites
        private set
    private var permissionDone: (() -> Unit)? = null
    private var pageSource:String?=null
    private var pageCache:List<String> = listOf("dashboard","weather","agenda","monitor","zen")
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (::terminal.isInitialized) { applyInk(); redraw() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("phosphor", MODE_PRIVATE)
        device = DeviceRepository(this) { if (::terminal.isInitialized) redraw() }
        frame = FrameLayout(this)
        dock = Dock(this)
        favorites = Favorites(this)
        terminal = TerminalView(this)
        frame.addView(terminal, FrameLayout.LayoutParams(-1,-1))
        search = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            isSingleLine = true
            setPadding(12,0,12,0)
            hint = "> find an app or search the web"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { terminal.query=s.toString();terminal.drawerScroll=0f;redraw() }
                override fun afterTextChanged(s:Editable?) {}
            })
            setOnEditorActionListener { _, _, _ -> terminal.launchFirst(); true }
        }
        frame.addView(search, FrameLayout.LayoutParams(-1,52).apply { topMargin=46 })
        frame.addOnLayoutChangeListener { _,_,_,r,_,_,_,_,_ ->
            val scale=r/540f
            search.layoutParams = (search.layoutParams as FrameLayout.LayoutParams).apply { topMargin=(48*scale).toInt();height=(54*scale).toInt();leftMargin=(16*scale).toInt();rightMargin=(16*scale).toInt() }
        }
        art = AsciiArt(this, frame)
        pageTransition = PageTransition(this, frame)
        val root = FrameLayout(this).apply {
            addView(frame, FrameLayout.LayoutParams(-1, -1))
            addView(pageTransition, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyInk()
    }
    private fun applyInk() {
        val ink=Inks.get(prefs.getString("theme","phosphor"))
        window.statusBarColor=ink.bg;window.navigationBarColor=ink.bg
        window.decorView.setBackgroundColor(ink.bg)
        if(Build.VERSION.SDK_INT>=29){window.isNavigationBarContrastEnforced=false;window.isStatusBarContrastEnforced=false}
        if(Build.VERSION.SDK_INT>=30)window.decorView.windowInsetsController?.setSystemBarsAppearance(
            if(ink.print)android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        window.decorView.systemUiVisibility=if(ink.print) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        if(::search.isInitialized){search.setTextColor(ink.fg);search.setHintTextColor(ink.dim)}
    }
    override fun onResume() { super.onResume();device.start();art.resume();terminal.running=true;redraw() }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus&&::prefs.isInitialized)applyInk()}
    override fun onPause() { finishPageTransition(true);terminal.running=false;art.pause();device.stop();super.onPause() }
    override fun onDestroy() { finishPageTransition();prefs.unregisterOnSharedPreferenceChangeListener(prefListener);art.close();terminal.close();super.onDestroy() }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);drawer(false);terminal.home() }
    override fun onBackPressed() { if(terminal.drawer)drawer(false) else terminal.home() }
    override fun redraw() { if(::terminal.isInitialized)terminal.postInvalidateOnAnimation() }
    fun openAgenda() { terminal.showPage("agenda") }
    override fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    override fun open(intent:Intent) {
        try { startActivity(intent) } catch(e: android.content.ActivityNotFoundException) { toast("No app handles this action") } catch(e: SecurityException) { toast("Permission required in Android settings") }
    }
    override fun input(title:String,initial:String,onDone:(String)->Unit) = inputDialog(title,initial,false,"",onDone)
    internal fun inputLine(title:String,initial:String,hint:String,onDone:(String)->Unit) = inputDialog(title,initial,true,hint,onDone)
    private fun inputDialog(title:String,initial:String,singleLine:Boolean,hint:String,onDone:(String)->Unit) {
        val ink=Inks.get(prefs.getString("theme","phosphor"))
        val edit=EditText(this).apply { setText(initial);setSelection(text.length);this.hint=hint;isSingleLine=singleLine;typeface=Typeface.MONOSPACE;setTextColor(ink.fg);setBackgroundColor(ink.bg);setPadding(24,18,24,18);minLines=if(!singleLine&&title.contains("note",true))6 else 1;maxLines=if(singleLine)1 else 12;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or if(singleLine)0 else android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val d=dialogBuilder(title).setView(edit).setPositiveButton("save") { _,_->onDone(edit.text.toString());redraw() }.setNegativeButton("cancel",null).create()
        d.setOnShowListener { edit.requestFocus();d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);styleDialog(d) };d.show()
    }
    override fun choose(title:String,options:List<String>,onChoose:(Int)->Unit) { val d=dialogBuilder(title).setItems(options.toTypedArray()) { _,i->onChoose(i);redraw() }.setNegativeButton("close",null).create();d.setOnShowListener{styleDialog(d)};d.show() }
    private fun dialogBuilder(title: String): AlertDialog.Builder = AlertDialog.Builder(this)
        .setCustomTitle(NativeDialogs.title(this, title, Inks.get(prefs.getString("theme", "phosphor"))))
    private fun styleDialog(d:AlertDialog) {
        NativeDialogs.style(d, Inks.get(prefs.getString("theme","phosphor")))
    }
    fun pickApp(title: String, selected: AppEntry?, allowEmpty: Boolean, onChoose: (AppEntry?) -> Unit) {
        AppPicker(this).single(title, selected, allowEmpty, onChoose)
    }
    fun pickApps(title: String, selected: List<String>, onSave: (List<String>) -> Unit) {
        AppPicker(this).multiple(title, selected, onSave)
    }
    internal fun animatePageChange(direction: Int, swap: () -> Unit) {
        pageTransition.start(PageAnimation.fromKey(prefs.getString("pageAnimation", null)), direction,
            Inks.get(prefs.getString("theme", "phosphor")), device.saver, swap)
    }
    internal fun finishPageTransition(applyPending: Boolean = false) {
        if (::pageTransition.isInitialized) pageTransition.finish(applyPending)
    }
    fun drawer(show:Boolean) {
        finishPageTransition()
        terminal.drawer=show;terminal.drawerScroll=0f
        search.visibility=if(show)View.VISIBLE else View.GONE
        val keyboard=getSystemService(InputMethodManager::class.java)
        if(show){search.setText("");search.requestFocus();keyboard.showSoftInput(search,InputMethodManager.SHOW_IMPLICIT)} else { keyboard.hideSoftInputFromWindow(search.windowToken,0);search.clearFocus();search.setText("");terminal.requestFocus() }
        redraw()
    }
    fun permission(name:String,done:()->Unit) {
        if(checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED){done();return}
        permissionDone=done;requestPermissions(arrayOf(name),10)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==10){val cb=permissionDone;permissionDone=null;if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)cb?.invoke() else toast("Permission is optional; this panel stays unavailable")};redraw() }
    fun quick(name:String) {
        when {
            name=="calc" -> terminal.showPage("calc")
            name=="search" -> drawer(true)
            name=="notifications" -> expandNotifications()
            name=="torch" && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED -> permission(Manifest.permission.CAMERA){device.quickAction(name)}
            name=="bluetooth" && Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED -> permission(Manifest.permission.BLUETOOTH_CONNECT){device.quickAction(name)}
            else -> device.quickAction(name)
        }
        redraw()
    }
    private fun expandNotifications() {
        try {
            val bar=getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar)
        } catch(e:ReflectiveOperationException) { toast("Swipe down from Android's status bar to open notifications") }
    }
    fun pages():List<String> {
        val raw=prefs.getString("pages","")?:""
        if(raw!=pageSource) {
            pageSource=raw
            pageCache=try { val a=JSONArray(raw);List(a.length().coerceIn(1,10)){a.getString(it)} }
            catch(e:Exception){listOf("dashboard","weather","agenda","monitor","zen")}
        }
        return pageCache
    }
    private fun savePages(p:List<String>) { prefs.edit().putString("pages",JSONArray(p).toString()).apply();terminal.finishPreview();terminal.pageIndex=terminal.pageIndex.coerceIn(0,p.lastIndex);redraw() }
    fun editPage() {
        val p=pages().toMutableList();val current=terminal.pageIndex.coerceIn(0,p.lastIndex)
        choose("~/pages · ${current+1}/${p.size}",listOf("change ${p[current]}","add page (${p.size}/10)","remove this page","reorder pages")) { n->
            when(n){
                0->choosePage {p[current]=it;savePages(p)}
                1->if(p.size<10)choosePage {p.add(current+1,it);savePages(p);terminal.pageIndex=current+1} else toast("At most ten pages")
                2->if(p.size>1){p.removeAt(current);savePages(p)} else toast("Keep at least one page")
                3->choose("move ${p[current]} before",p) {target->val item=p.removeAt(current);p.add(target,item);savePages(p);terminal.pageIndex=target}
            }
        }
    }
    private fun choosePage(done:(String)->Unit) { val kinds=listOf("dashboard","weather","inbox","monitor","zen","art","agenda","media","apps","favorites","today","notes","todo","pomodoro","calc","world","sky","battery","storage","fetch");choose("page type",kinds){done(kinds[it])} }
    fun appMenu(app:AppEntry) {
        choose(app.label,listOf(if(dock.isPinned(app))"unpin from dock" else "pin to dock",if(favorites.isFavorite(app))"remove from favorites" else "add to favorites","app info","uninstall")){ n->when(n){
            0->dock.togglePin(app)
            1->favorites.toggle(app)
            2->open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.component.packageName}")))
            3->open(Intent(Intent.ACTION_DELETE,Uri.parse("package:${app.component.packageName}")))
        }}
    }
    fun settings() {
        choose("◆ phosphor · settings",listOf(
            "theme · ${prefs.getString("theme","phosphor")}","ambience · ${prefs.getString("ambience","rain")}","CRT glass · ${prefs.getBoolean("crt",true)}","widget frames · ${prefs.getBoolean("frames",true)}","clock · ${if(prefs.getBoolean("clock24",true))"24h" else "12h"}",
            "units · °${prefs.getString("units","c")}","text size · ${prefs.getString("textSize","medium")}","pages · ${pages().size}/10","page animation · ${PageAnimation.fromKey(prefs.getString("pageAnimation",null)).label}","quick actions","weather / location","optional permissions","ASCII art · ${art.selected.name}","lock screen","set default launcher","about / privacy","dock apps","calendar events","open favorites"
        )) { n->when(n){
            0->choose("fourteen inks",Inks.all.map{it.name}){prefs.edit().putString("theme",Inks.all[it].name).apply()}
            1->{val a=listOf("off","rain","stars","embers","snow");choose("ambience",a){prefs.edit().putString("ambience",a[it]).apply()}}
            2->prefs.edit().putBoolean("crt",!prefs.getBoolean("crt",true)).apply()
            3->prefs.edit().putBoolean("frames",!prefs.getBoolean("frames",true)).apply()
            4->prefs.edit().putBoolean("clock24",!prefs.getBoolean("clock24",true)).apply()
            5->choose("temperature / wind",listOf("Celsius · km/h","Fahrenheit · mph")){prefs.edit().putString("units",if(it==0)"c" else "f").apply()}
            6->{val a=listOf("small","medium","large","xl");choose("text size",a){prefs.edit().putString("textSize",a[it]).apply()}}
            7->editPage()
            8->{
                val current=PageAnimation.fromKey(prefs.getString("pageAnimation",null))
                choose("page animation",PageAnimation.entries.map{if(it==current)"● ${it.label}" else it.label}){prefs.edit().putString("pageAnimation",PageAnimation.entries[it].key).apply()}
            }
            9->editQuick()
            10->weatherSettings()
            11->permissions()
            12->artSettings()
            13->lockSettings()
            14->if(Build.VERSION.SDK_INT>=29){val role=getSystemService(RoleManager::class.java);if(role.isRoleHeld(RoleManager.ROLE_HOME))toast("Phosphor is already your default launcher") else if(role.isRoleAvailable(RoleManager.ROLE_HOME))startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_HOME),41) else open(Intent(Settings.ACTION_HOME_SETTINGS))} else open(Intent(Settings.ACTION_HOME_SETTINGS))
            15->{val d=dialogBuilder("Phosphor · independent implementation").setMessage("Native terminal launcher inspired by phosphor.zanoski.com. Not the original commercial app or affiliated with its author.\n\nNo ads, accounts or analytics. Apps, calendar, notes and notifications remain on this device. Weather sends the chosen city to Open-Meteo geocoding and coordinates to Open-Meteo forecasts. If that forecast service is unreachable, coordinates are sent to wttr.in instead. Device location is rounded to two decimals; weather is requested only when configured.\n\nascii.rest artwork by @bas3line is bundled under the MIT license and plays entirely offline. License: settings → ASCII art.\n\nSwipe ↑ apps · ↓ system shade · ← → pages. Hold blank space for pages; hold an app for dock/favorites/info/uninstall. All permissions are optional.").setPositiveButton("close",null).create();d.setOnShowListener{styleDialog(d)};d.show()}
            16->dock.settings()
            17->openAgenda()
            18->terminal.showPage("favorites")
        }}
    }
    fun artSettings() {
        choose("ascii.rest · offline art",listOf(
            "browse catalogue · ${art.pieces.size} pieces","open art page",
            "Zen scene · ${if(prefs.getBoolean("ascii_zen",false))"ascii.rest" else "weather landscape"}",
            "colors · ${if(prefs.getBoolean("ascii_mono",false))"launcher ink" else "original palette"}",
            "animation · ${if(prefs.getBoolean("ascii_paused",false))"paused" else "playing"}",
            "retry renderer","ascii.rest · MIT license"
        )) { n->when(n) {
            0->chooseArt(true)
            1->terminal.showPage("art")
            2->{prefs.edit().putBoolean("ascii_zen",!prefs.getBoolean("ascii_zen",false)).apply();terminal.showPage("zen")}
            3->prefs.edit().putBoolean("ascii_mono",!prefs.getBoolean("ascii_mono",false)).apply()
            4->prefs.edit().putBoolean("ascii_paused",!prefs.getBoolean("ascii_paused",false)).apply()
            5->{art.retry();terminal.showPage("art")}
            6->{
                val license=assets.open("ascii/LICENSE").bufferedReader().use{it.readText()}
                val d=dialogBuilder("ascii.rest · @bas3line · MIT").setMessage(license).setPositiveButton("close",null).create()
                d.setOnShowListener{styleDialog(d)};d.show()
            }
        }}
    }
    fun chooseArt(showPreview:Boolean=false) {
        val pieces=art.pieces
        val categories=pieces.groupBy{it.category}.toSortedMap()
        fun select(matches:List<ArtPiece>) {
            if(matches.isEmpty()){toast("No matching artwork");return}
            choose("choose ASCII artwork",matches.map{if(it.id==art.selected.id)"● ${it.name}" else it.name}){n->
                prefs.edit().putString("ascii_piece",matches[n].id).apply()
                if(showPreview)terminal.showPage("art")
            }
        }
        val names=categories.keys.toList()
        choose("ascii.rest · ${pieces.size} pieces",listOf("search by name","all pieces · ${pieces.size}")+names.map{"$it · ${categories.getValue(it).size}"}){n->
            when(n) {
                0->input("find ASCII art",""){query->val q=query.trim();select(pieces.filter{it.name.contains(q,true)||it.id.contains(q,true)||it.note.contains(q,true)})}
                1->select(pieces)
                else->select(categories.getValue(names[n-2]))
            }
        }
    }
    fun weatherSettings() {
        choose("weather · Open-Meteo / wttr.in",listOf("choose city","use approximate location","refresh","disable / clear weather")){n->when(n){
            0->input("weather city · Самара, RU",prefs.getString("weather_city","")?:""){ city->if(city.isNotBlank())device.refreshWeather(city.trim()) }
            1->permission(Manifest.permission.ACCESS_COARSE_LOCATION){device.refreshWeather("")}
            2->device.refreshWeather()
            3->{prefs.edit().remove("weather_city").remove("weather_cache").putBoolean("weather_enabled",false).apply();device.refreshWeather()}
        }}
    }
    private fun permissions() {
        choose("optional access",listOf("notifications + media controls","calendar events","weather location","camera / torch","Bluetooth state","timer notifications","precise timer alarm","do not disturb")){n->when(n){
            0->open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            1->permission(Manifest.permission.READ_CALENDAR){device.refreshCalendar()}
            2->permission(Manifest.permission.ACCESS_COARSE_LOCATION){device.refreshWeather("")}
            3->permission(Manifest.permission.CAMERA){}
            4->if(Build.VERSION.SDK_INT>=31)permission(Manifest.permission.BLUETOOTH_CONNECT){}
            5->if(Build.VERSION.SDK_INT>=33)permission(Manifest.permission.POST_NOTIFICATIONS){}
            6->if(Build.VERSION.SDK_INT>=31)open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
            7->open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }}
    }
    private fun editQuick() {
        val actions=listOf("torch","wifi","bluetooth","sound","do not disturb","location","battery saver","settings","internet","alarm","calendar","camera","calc","notifications","display","volume","airplane mode","hotspot","search")
        val chosen=terminal.quickNames().toMutableList()
        val checked=actions.map{it in chosen}.toBooleanArray()
        val d=dialogBuilder("quick · up to seven").setMultiChoiceItems(actions.toTypedArray(),checked){dialog,i,yes->
            if(yes&&chosen.size>=7){(dialog as AlertDialog).listView.setItemChecked(i,false);toast("At most seven actions")} else if(yes)chosen.add(actions[i]) else chosen.remove(actions[i])
        }.setPositiveButton("save"){_,_->prefs.edit().putString("quick",JSONArray(chosen).toString()).apply()}.setNegativeButton("cancel",null).create();d.setOnShowListener{styleDialog(d)};d.show()
    }
    private fun lockSettings() {
        choose("~/lock · live wallpaper",listOf("set as wallpaper / lock screen","theme · ${prefs.getString("lockTheme","match")}","clock · ${prefs.getBoolean("lockClock",true)}","weather · ${prefs.getBoolean("lockWeather",true)}","battery · ${prefs.getBoolean("lockBattery",true)}","next event · ${prefs.getBoolean("lockEvent",true)}","notifications · ${prefs.getBoolean("lockNotifications",true)}")){n->when(n){
            0->{
                val d=dialogBuilder("Android draws its own clock")
                    .setMessage("For the matching layout, choose a small / compact clock in Android's Wallpaper & style → Lock screen settings. A large double-line system clock can overlap this live wallpaper; Phosphor cannot move or replace it.\n\nThe next screen lets you choose where to apply the wallpaper. Availability depends on your Android version and device.")
                    .setPositiveButton("continue"){_,_->open(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this,PhosphorWallpaper::class.java)))}
                    .setNegativeButton("cancel",null).create()
                d.setOnShowListener{styleDialog(d)};d.show()
            }
            1->{val a=listOf("match")+Inks.all.map{it.name};choose("lock theme",a){prefs.edit().putString("lockTheme",a[it]).apply()}}
            else->{val key=listOf("lockClock","lockWeather","lockBattery","lockEvent","lockNotifications")[n-2];prefs.edit().putBoolean(key,!prefs.getBoolean(key,true)).apply()}
        }}
    }
}
