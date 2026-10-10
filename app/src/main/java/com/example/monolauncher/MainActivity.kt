package com.example.monolauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Basit bir ana ekran (launcher). Duvar kağıdının rengini okur ve tüm uygulama
 * simgelerini (3. parti dahil) tek renk tonuna boyar.
 * Modlar: 0 = Otomatik (duvar kağıdı rengi), 1 = Siyah-Beyaz, 2 = Orijinal
 */
class MainActivity : Activity() {

    data class AppItem(
        val label: String,
        val component: ComponentName,
        val user: UserHandle,
        val icon: Bitmap
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var modeBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val items = ArrayList<AppItem>()
    private val appAdapter = AppAdapter()
    private val modeNames = arrayOf("Otomatik", "Siyah-Beyaz", "Orijinal")
    @Volatile private var generation = 0

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reload()
    }
    private val wallListener = WallpaperManager.OnColorsChangedListener { _, _ -> reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mono", MODE_PRIVATE)
        val d = resources.displayMetrics.density

        val grid = GridView(this).apply {
            numColumns = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = (16 * d).toInt()
            clipToPadding = false
            setPadding((12 * d).toInt(), (96 * d).toInt(), (12 * d).toInt(), (24 * d).toInt())
            setAdapter(appAdapter)
            setOnItemClickListener { _, _, pos, _ -> launch(items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> appInfo(items[pos]); true }
        }

        modeBtn = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * d
                setColor(Color.argb(140, 40, 40, 40))
            }
            setOnClickListener {
                prefs.edit().putInt("mode", (prefs.getInt("mode", 0) + 1) % 3).apply()
                reload()
            }
        }

        val root = FrameLayout(this)
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            modeBtn,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = (44 * d).toInt()
                rightMargin = (16 * d).toInt()
            }
        )
        setContentView(root)

        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallListener, ui)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallListener)
        unregisterReceiver(pkgReceiver)
    }

    @Deprecated("Launcher: geri tuşu bir şey yapmasın")
    override fun onBackPressed() {}

    private fun launch(a: AppItem) {
        getSystemService(LauncherApps::class.java).startMainActivity(a.component, a.user, null, null)
    }

    private fun appInfo(a: AppItem) {
        getSystemService(LauncherApps::class.java).startAppDetailsActivity(a.component, a.user, null, null)
    }

    /** Duvar kağıdına göre (koyu, açık) renk çifti. */
    private fun palette(mode: Int): Pair<Int, Int> {
        val bw = Pair(Color.rgb(16, 16, 16), Color.rgb(242, 242, 242))
        if (mode != 0) return bw
        val colors = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return bw
        val hsv = FloatArray(3)
        Color.colorToHSV(colors.primaryColor.toArgb(), hsv)
        if (hsv[1] < 0.08f) return bw // duvar kağıdı zaten gri/siyah-beyaz
        val dark = Color.HSVToColor(floatArrayOf(hsv[0], minOf(hsv[1], 0.9f), 0.18f))
        val light = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.25f, 0.97f))
        return Pair(dark, light)
    }

    private fun reload() {
        val mode = prefs.getInt("mode", 0)
        modeBtn.text = "◐ " + modeNames[mode]
        val (dark, light) = palette(mode)
        val gen = ++generation
        Thread {
            val la = getSystemService(LauncherApps::class.java)
            val um = getSystemService(UserManager::class.java)
            val dpi = resources.displayMetrics.densityDpi
            val size = (resources.displayMetrics.density * 56).toInt() * 2
            val list = ArrayList<AppItem>()
            for (u in um.userProfiles) {
                for (a in la.getActivityList(null, u)) {
                    val bmp = recolor(a.getIcon(dpi), size, mode, dark, light)
                    list.add(AppItem(a.label.toString(), a.componentName, u, bmp))
                }
            }
            list.sortBy { it.label.lowercase() }
            ui.post {
                if (gen != generation) return@post
                items.clear()
                items.addAll(list)
                appAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    /** İkonu gri tona çevirip (koyu -> açık) renk aralığına eşler. Alfa korunur. */
    private fun recolor(d: Drawable, size: Int, mode: Int, dark: Int, light: Int): Bitmap {
        val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(src))
        if (mode == 2) return src

        val dk = intArrayOf(Color.red(dark), Color.green(dark), Color.blue(dark))
        val lt = intArrayOf(Color.red(light), Color.green(light), Color.blue(light))
        val m = FloatArray(20)
        for (i in 0..2) {
            val s = (lt[i] - dk[i]) / 255f
            m[i * 5] = s * 0.213f
            m[i * 5 + 1] = s * 0.715f
            m[i * 5 + 2] = s * 0.072f
            m[i * 5 + 4] = dk[i].toFloat()
        }
        m[18] = 1f // alfa kanalı aynen kalsın

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix(m))
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt())
                })
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setShadowLayer(3f, 0f, 1f, Color.BLACK)
                })
            }
            (row.getChildAt(0) as ImageView).setImageBitmap(items[position].icon)
            (row.getChildAt(1) as TextView).text = items[position].label
            return row
        }
    }
}
