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
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "language") {
            L10n.refresh()
            if (::search.isInitialized) search.hint = L10n.text(R.string.menus_search_hint)
        }
        if (::terminal.isInitialized) { applyInk(); redraw() }
    }

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
            hint = L10n.text(R.string.menus_search_hint)
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
        try { startActivity(intent) } catch(e: android.content.ActivityNotFoundException) { toast(L10n.text(R.string.menus_no_handler)) } catch(e: SecurityException) { toast(L10n.text(R.string.menus_permission_required)) }
    }
    override fun input(title:String,initial:String,onDone:(String)->Unit) = inputDialog(title,initial,false,"",1,onDone)
    override fun inputNotes(initial:String,onDone:(String)->Unit) = inputDialog(L10n.text(R.string.tools_notes_title),initial,false,"",6,onDone)
    internal fun inputLine(title:String,initial:String,hint:String,onDone:(String)->Unit) = inputDialog(title,initial,true,hint,1,onDone)
    private fun inputDialog(title:String,initial:String,singleLine:Boolean,hint:String,minimumLines:Int,onDone:(String)->Unit) {
        val ink=Inks.get(prefs.getString("theme","phosphor"))
        val edit=EditText(this).apply { setText(initial);setSelection(text.length);this.hint=hint;isSingleLine=singleLine;typeface=Typeface.MONOSPACE;setTextColor(ink.fg);setBackgroundColor(ink.bg);setPadding(24,18,24,18);minLines=minimumLines;maxLines=if(singleLine)1 else 12;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or if(singleLine)0 else android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val d=dialogBuilder(title).setView(edit).setPositiveButton(L10n.text(R.string.common_save)) { _,_->onDone(edit.text.toString());redraw() }.setNegativeButton(L10n.text(R.string.common_cancel),null).create()
        d.setOnShowListener { edit.requestFocus();d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);styleDialog(d) };d.show()
    }
    override fun choose(title:String,options:List<String>,onChoose:(Int)->Unit) { val d=dialogBuilder(title).setItems(options.toTypedArray()) { _,i->onChoose(i);redraw() }.setNegativeButton(L10n.text(R.string.common_close),null).create();d.setOnShowListener{styleDialog(d)};d.show() }
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
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==10){val cb=permissionDone;permissionDone=null;if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)cb?.invoke() else toast(L10n.text(R.string.menus_permission_optional))};redraw() }
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
        } catch(e:ReflectiveOperationException) { toast(L10n.text(R.string.menus_notification_swipe)) }
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
        choose(L10n.text(R.string.menus_pages_title,current+1,p.size),listOf(L10n.text(R.string.menus_change_page,L10n.pageName(p[current])),L10n.text(R.string.menus_add_page,p.size),L10n.text(R.string.menus_remove_page),L10n.text(R.string.menus_reorder_pages))) { n->
            when(n){
                0->choosePage {p[current]=it;savePages(p)}
                1->if(p.size<10)choosePage {p.add(current+1,it);savePages(p);terminal.pageIndex=current+1} else toast(L10n.text(R.string.menus_max_pages))
                2->if(p.size>1){p.removeAt(current);savePages(p)} else toast(L10n.text(R.string.menus_min_pages))
                3->choose(L10n.text(R.string.menus_move_before,L10n.pageName(p[current])),p.map(L10n::pageName)) {target->val item=p.removeAt(current);p.add(target,item);savePages(p);terminal.pageIndex=target}
            }
        }
    }
    private fun choosePage(done:(String)->Unit) { val kinds=listOf("dashboard","weather","inbox","monitor","zen","art","agenda","media","apps","favorites","today","notes","todo","pomodoro","calc","world","sky","battery","storage","fetch");choose(L10n.text(R.string.menus_page_type),kinds.map(L10n::pageName)){done(kinds[it])} }
    fun appMenu(app:AppEntry) {
        choose(app.label,listOf(L10n.text(if(dock.isPinned(app))R.string.menus_unpin else R.string.menus_pin),L10n.text(if(favorites.isFavorite(app))R.string.menus_unfavorite else R.string.menus_favorite),L10n.text(R.string.menus_app_info),L10n.text(R.string.menus_uninstall))){ n->when(n){
            0->dock.togglePin(app)
            1->favorites.toggle(app)
            2->open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.component.packageName}")))
            3->open(Intent(Intent.ACTION_DELETE,Uri.parse("package:${app.component.packageName}")))
        }}
    }
    fun settings() {
        choose(L10n.text(R.string.menus_settings),listOf(
            L10n.text(R.string.menus_language_value,L10n.text(if(L10n.isRussian)R.string.menus_language_russian else R.string.menus_language_english)),
            L10n.text(R.string.menus_theme_value,L10n.valueName(prefs.getString("theme","phosphor")!!)),
            L10n.text(R.string.menus_ambience_value,L10n.valueName(prefs.getString("ambience","rain")!!)),
            L10n.text(R.string.menus_crt_value,L10n.onOff(prefs.getBoolean("crt",true))),
            L10n.text(R.string.menus_frames_value,L10n.onOff(prefs.getBoolean("frames",true))),
            L10n.text(R.string.menus_clock_value,L10n.text(if(prefs.getBoolean("clock24",true))R.string.menus_clock_24 else R.string.menus_clock_12)),
            L10n.text(R.string.menus_units_value,if(prefs.getString("units","c")=="f")"F" else "C"),
            L10n.text(R.string.menus_text_size_value,L10n.valueName(prefs.getString("textSize","medium")!!)),
            L10n.text(R.string.menus_pages_value,pages().size),
            L10n.text(R.string.menus_animation_value,PageAnimation.fromKey(prefs.getString("pageAnimation",null)).label),
            L10n.text(R.string.menus_quick_actions),L10n.text(R.string.menus_weather_location),
            L10n.text(R.string.menus_optional_permissions),L10n.text(R.string.menus_art_value,art.selected.displayName),
            L10n.text(R.string.menus_lock_screen),L10n.text(R.string.menus_default_launcher),
            L10n.text(R.string.menus_about_privacy),L10n.text(R.string.menus_dock_apps),
            L10n.text(R.string.menus_calendar_events),L10n.text(R.string.menus_open_favorites),
        )) { n->when(n){
            0->choose(L10n.text(R.string.menus_language),listOf(L10n.text(R.string.menus_language_english),L10n.text(R.string.menus_language_russian))) { index ->
                prefs.edit().putString("language",if(index==1)"ru" else "en").apply()
                L10n.refresh()
                search.hint=L10n.text(R.string.menus_search_hint)
                redraw()
            }
            1->choose(L10n.text(R.string.menus_inks),Inks.all.map{L10n.valueName(it.name)}){prefs.edit().putString("theme",Inks.all[it].name).apply()}
            2->{val a=listOf("off","rain","stars","embers","snow");choose(L10n.text(R.string.menus_ambience),a.map(L10n::valueName)){prefs.edit().putString("ambience",a[it]).apply()}}
            3->prefs.edit().putBoolean("crt",!prefs.getBoolean("crt",true)).apply()
            4->prefs.edit().putBoolean("frames",!prefs.getBoolean("frames",true)).apply()
            5->prefs.edit().putBoolean("clock24",!prefs.getBoolean("clock24",true)).apply()
            6->choose(L10n.text(R.string.menus_temperature_wind),listOf(L10n.text(R.string.menus_celsius),L10n.text(R.string.menus_fahrenheit))){prefs.edit().putString("units",if(it==0)"c" else "f").apply()}
            7->{val a=listOf("small","medium","large","xl");choose(L10n.text(R.string.menus_text_size),a.map(L10n::valueName)){prefs.edit().putString("textSize",a[it]).apply()}}
            8->editPage()
            9->{
                val current=PageAnimation.fromKey(prefs.getString("pageAnimation",null))
                choose(L10n.text(R.string.menus_page_animation),PageAnimation.entries.map{if(it==current)"● ${it.label}" else it.label}){prefs.edit().putString("pageAnimation",PageAnimation.entries[it].key).apply()}
            }
            10->editQuick()
            11->weatherSettings()
            12->permissions()
            13->artSettings()
            14->lockSettings()
            15->if(Build.VERSION.SDK_INT>=29){val role=getSystemService(RoleManager::class.java);if(role.isRoleHeld(RoleManager.ROLE_HOME))toast(L10n.text(R.string.menus_already_default)) else if(role.isRoleAvailable(RoleManager.ROLE_HOME))startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_HOME),41) else open(Intent(Settings.ACTION_HOME_SETTINGS))} else open(Intent(Settings.ACTION_HOME_SETTINGS))
            16->{val d=dialogBuilder(L10n.text(R.string.menus_about_title)).setMessage(L10n.text(R.string.menus_about_body)).setPositiveButton(L10n.text(R.string.common_close),null).create();d.setOnShowListener{styleDialog(d)};d.show()}
            17->dock.settings()
            18->openAgenda()
            19->terminal.showPage("favorites")
        }}
    }
    fun artSettings() {
        choose(L10n.text(R.string.menus_offline_art),listOf(
            L10n.text(R.string.menus_browse_art,art.pieces.size),L10n.text(R.string.menus_open_art),
            L10n.text(R.string.menus_zen_scene,if(prefs.getBoolean("ascii_zen",false))"ascii.rest" else L10n.text(R.string.menus_weather_landscape)),
            L10n.text(R.string.menus_colors_value,L10n.text(if(prefs.getBoolean("ascii_mono",false))R.string.menus_launcher_ink else R.string.menus_original_palette)),
            L10n.text(R.string.menus_art_animation,L10n.text(if(prefs.getBoolean("ascii_paused",false))R.string.menus_paused else R.string.menus_playing)),
            L10n.text(R.string.menus_retry_renderer),L10n.text(R.string.menus_art_license)
        )) { n->when(n) {
            0->chooseArt(true)
            1->terminal.showPage("art")
            2->{prefs.edit().putBoolean("ascii_zen",!prefs.getBoolean("ascii_zen",false)).apply();terminal.showPage("zen")}
            3->prefs.edit().putBoolean("ascii_mono",!prefs.getBoolean("ascii_mono",false)).apply()
            4->prefs.edit().putBoolean("ascii_paused",!prefs.getBoolean("ascii_paused",false)).apply()
            5->{art.retry();terminal.showPage("art")}
            6->{
                val license=assets.open("ascii/LICENSE").bufferedReader().use{it.readText()}
                val d=dialogBuilder("ascii.rest · @bas3line · MIT").setMessage(license).setPositiveButton(L10n.text(R.string.common_close),null).create()
                d.setOnShowListener{styleDialog(d)};d.show()
            }
        }}
    }
    fun chooseArt(showPreview:Boolean=false) {
        val pieces=art.pieces
        val categories=pieces.groupBy{it.category}.toSortedMap()
        fun select(matches:List<ArtPiece>) {
            if(matches.isEmpty()){toast(L10n.text(R.string.menus_no_art));return}
            choose(L10n.text(R.string.menus_choose_art),matches.map{if(it.id==art.selected.id)"● ${it.displayName}" else it.displayName}){n->
                prefs.edit().putString("ascii_piece",matches[n].id).apply()
                if(showPreview)terminal.showPage("art")
            }
        }
        val names=categories.keys.toList()
        choose(L10n.text(R.string.menus_art_count,pieces.size),listOf(L10n.text(R.string.menus_search_name),L10n.text(R.string.menus_all_art,pieces.size))+names.map{"${ArtLocale.category(it)} · ${categories.getValue(it).size}"}){n->
            when(n) {
                0->input(L10n.text(R.string.menus_find_art),""){query->val q=query.trim();select(pieces.filter{it.name.contains(q,true)||it.id.contains(q,true)||it.note.contains(q,true)||it.category.contains(q,true)||it.displayName.contains(q,true)||it.displayNote.contains(q,true)||it.displayCategory.contains(q,true)})}
                1->select(pieces)
                else->select(categories.getValue(names[n-2]))
            }
        }
    }
    fun weatherSettings() {
        val style=WeatherArtStyle.fromKey(prefs.getString("weather_art",null))
        choose(L10n.text(R.string.menus_weather_title),listOf(L10n.text(R.string.menus_choose_city),L10n.text(R.string.menus_approximate_location),L10n.text(R.string.menus_refresh),L10n.text(R.string.menus_weather_style,style.label),L10n.text(R.string.menus_clear_weather))){n->when(n){
            0->input(L10n.text(R.string.menus_weather_city),prefs.getString("weather_city","")?:""){ city->if(city.isNotBlank())device.refreshWeather(city.trim()) }
            1->permission(Manifest.permission.ACCESS_COARSE_LOCATION){device.refreshWeather("")}
            2->device.refreshWeather()
            3->choose(L10n.text(R.string.menus_ascii_weather),WeatherArtStyle.entries.map{if(it==style)"● ${it.label}" else it.label}){prefs.edit().putString("weather_art",WeatherArtStyle.entries[it].key).apply()}
            4->{prefs.edit().remove("weather_city").remove("weather_cache").putBoolean("weather_enabled",false).apply();device.refreshWeather()}
        }}
    }
    private fun permissions() {
        choose(L10n.text(R.string.menus_optional_access),listOf(L10n.text(R.string.menus_notifications_media),L10n.text(R.string.menus_calendar_events),L10n.text(R.string.menus_weather_permission),L10n.text(R.string.menus_camera_torch),L10n.text(R.string.menus_bluetooth_state),L10n.text(R.string.menus_timer_notifications),L10n.text(R.string.menus_precise_alarm),L10n.actionName("do not disturb"))){n->when(n){
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
        val d=dialogBuilder(L10n.text(R.string.menus_quick_limit)).setMultiChoiceItems(actions.map(L10n::actionName).toTypedArray(),checked){dialog,i,yes->
            if(yes&&chosen.size>=7){(dialog as AlertDialog).listView.setItemChecked(i,false);toast(L10n.text(R.string.menus_max_actions))} else if(yes)chosen.add(actions[i]) else chosen.remove(actions[i])
        }.setPositiveButton(L10n.text(R.string.common_save)){_,_->prefs.edit().putString("quick",JSONArray(chosen).toString()).apply()}.setNegativeButton(L10n.text(R.string.common_cancel),null).create();d.setOnShowListener{styleDialog(d)};d.show()
    }
    private fun lockSettings() {
        choose(L10n.text(R.string.menus_live_wallpaper),listOf(L10n.text(R.string.menus_set_wallpaper),L10n.text(R.string.menus_theme_value,L10n.valueName(prefs.getString("lockTheme","match")!!)),L10n.text(R.string.menus_clock_value,L10n.onOff(prefs.getBoolean("lockClock",true))),L10n.text(R.string.menus_weather_value,L10n.onOff(prefs.getBoolean("lockWeather",true))),L10n.text(R.string.menus_battery_value,L10n.onOff(prefs.getBoolean("lockBattery",true))),L10n.text(R.string.menus_event_value,L10n.onOff(prefs.getBoolean("lockEvent",true))),L10n.text(R.string.menus_notifications_value,L10n.onOff(prefs.getBoolean("lockNotifications",true))))){n->when(n){
            0->{
                val d=dialogBuilder(L10n.text(R.string.menus_android_clock))
                    .setMessage(L10n.text(R.string.menus_wallpaper_instructions))
                    .setPositiveButton(L10n.text(R.string.menus_continue)){_,_->open(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this,PhosphorWallpaper::class.java)))}
                    .setNegativeButton(L10n.text(R.string.common_cancel),null).create()
                d.setOnShowListener{styleDialog(d)};d.show()
            }
            1->{val a=listOf("match")+Inks.all.map{it.name};choose(L10n.text(R.string.menus_lock_theme),a.map(L10n::valueName)){prefs.edit().putString("lockTheme",a[it]).apply()}}
            else->{val key=listOf("lockClock","lockWeather","lockBattery","lockEvent","lockNotifications")[n-2];prefs.edit().putBoolean(key,!prefs.getBoolean(key,true)).apply()}
        }}
    }
}
