package com.tapvpn.app

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AppTargetActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter
    private var all = emptyList<AppRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_targets)
        prefs = Prefs(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val list = findViewById<ListView>(R.id.list)
        val empty = findViewById<TextView>(R.id.empty)
        adapter = AppAdapter()
        list.adapter = adapter
        list.emptyView = empty
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        Thread {
            try {
                val rows = loadApps()
                runOnUiThread {
                    all = rows
                    adapter.filter("")
                }
            } catch (error: Throwable) {
                CrashLog.write(this, error)
                runOnUiThread {
                    empty.text = error.javaClass.simpleName + ": " + error.message
                }
            }
        }.start()
    }

    private fun loadApps(): List<AppRow> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val rows = ArrayList<AppRow>()
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        val infos = try {
            packageManager.queryIntentActivities(intent, flags)
        } catch (_: Exception) {
            packageManager.queryIntentActivities(intent, 0)
        }
        for (info in infos) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            val label = info.loadLabel(packageManager).toString()
            val icon = info.loadIcon(packageManager)
            rows.add(AppRow(pkg, label, icon))
        }
        rows.sortBy { it.label.lowercase() }
        return rows
    }

    private inner class AppAdapter : BaseAdapter() {
        private var shown = emptyList<AppRow>()

        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            val selected = prefs.targetPackages().contains(app.pkg)
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.label).text = app.label
            val mark = row.findViewById<View>(R.id.mark)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@AppTargetActivity, if (selected) R.color.on else R.color.bg_subtle))
            }
            mark.background = dot
            row.setOnClickListener {
                prefs.toggleTarget(app.pkg)
                notifyDataSetChanged()
            }
            return row
        }
    }
}

private data class AppRow(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
)
