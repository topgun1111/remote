package com.remotecontrol.controller

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.remotecontrol.shared.AppEntry
import com.remotecontrol.shared.AppsLogic
import com.remotecontrol.shared.LaunchResult

/**
 * The Agent's apps as a searchable list; tap an app to launch it on the Agent (Session 8, part 1). Session 8d: every row
 * shows the app's icon, fetched from the Agent by [AppIconLoader] (cached, at most 3 requests at a time).
 * Plain Views, no AndroidX. Meant to be shown full-screen (it fills its parent), like the viewer:
 * `setContentView(AppsPanel(this, { json -> connectionManager.send(json) }, onClose = { showConnectionScreen() },
 *  onLaunched = { openViewer() }))`.
 *
 * @param onClose  called by the Close button; the host removes the panel
 * @param onLaunched called after the Agent reports a started launch (optional: e.g. jump to the live view)
 */
class AppsPanel(
    context: Context,
    private val send: (String) -> Boolean,
    private val onClose: () -> Unit = {},
    private val onLaunched: (LaunchResult) -> Unit = {},
) : LinearLayout(context) {
    private val search = EditText(context).apply {
        hint = "Search apps"; setSingleLine(); textSize = 16f
    }
    private val hideSystem = CheckBox(context).apply { text = "Hide system apps"; textSize = 13f }
    private val refreshBtn = Button(context).apply { text = "Refresh"; isAllCaps = false }
    private val closeBtn = Button(context).apply { text = "Close"; isAllCaps = false }
    private val statusView = TextView(context).apply { textSize = 12f; visibility = View.GONE }
    private val list = ListView(context)
    private val icons = AppIconLoader(send)
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val adapter = object : ArrayAdapter<AppEntry>(context, 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val a = getItem(position)!!
            val text = row.getChildAt(1) as LinearLayout
            (text.getChildAt(0) as TextView).text = a.label
            (text.getChildAt(1) as TextView).text = a.pkg + if (a.system) "  (system)" else ""
            icons.bind(row.getChildAt(0) as ImageView, a.pkg)
            return row
        }

        /** Icon (40 dp) left of a two-line label, like `simple_list_item_2`. */
        private fun buildRow(): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(6), px(12), px(6))
                minimumHeight = px(56)
            }
            row.addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(px(40), px(40)))
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
            text.addView(TextView(context).apply { textSize = 16f; setSingleLine() })
            text.addView(TextView(context).apply { textSize = 12f; setSingleLine(); alpha = 0.7f })
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }
    }
    private val client = AppsClient(send, this::showList, this::showLaunch, this::showError)

    init {
        orientation = VERTICAL
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)

        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(search, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(refreshBtn); top.addView(closeBtn)
        addView(top)
        addView(hideSystem)
        addView(statusView)
        list.adapter = adapter
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        hideSystem.setOnCheckedChangeListener { _, _ -> applyFilter() }
        refreshBtn.setOnClickListener { status("Loading apps..."); client.refresh() }
        closeBtn.setOnClickListener { onClose() }
        list.setOnItemClickListener { _, _, pos, _ ->
            val a = adapter.getItem(pos) ?: return@setOnItemClickListener
            status("Launching ${a.label}...")
            client.launch(a.pkg)
        }
    }

    private fun applyFilter() {
        val shown = AppsLogic.filter(client.apps, search.text.toString(), hideSystem.isChecked)
        adapter.setNotifyOnChange(false)
        adapter.clear(); adapter.addAll(shown)
        adapter.notifyDataSetChanged()
    }

    private fun showList(apps: List<AppEntry>) {
        applyFilter()
        status(if (apps.isEmpty()) "The Agent reported no launchable apps." else "${apps.size} apps. Tap one to open it on the Agent.")
    }

    private fun showLaunch(r: LaunchResult) {
        status((if (r.ok) "" else "Failed: ") + r.detail)
        if (r.ok) onLaunched(r)
    }

    private fun showError(msg: String) = status(msg)

    private fun status(text: String) {
        statusView.text = text
        statusView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        icons.start()
        AppsHub.register(client)
        if (client.apps.isNotEmpty()) applyFilter()
        status("Loading apps..."); client.refresh()
    }

    override fun onDetachedFromWindow() {
        icons.stop()
        AppsHub.unregister(client); client.destroy(); super.onDetachedFromWindow()
    }
}
