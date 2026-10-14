package com.remotecontrol.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** "RC Files Setup": grant file access to the Agent (Session 8b) and self-test the folder listing. Plain Views. */
class FilesSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 16f }
        result = TextView(this).apply { textSize = 13f }
        val grant = Button(this).apply { text = "Grant file access"; isAllCaps = false; setOnClickListener { grant() } }
        val test = Button(this).apply { text = "Test: list internal storage"; isAllCaps = false; setOnClickListener { test() } }
        col.addView(TextView(this).apply { text = "Lets the Controller browse and download files from this phone's shared storage."; textSize = 13f })
        col.addView(status); col.addView(grant); col.addView(test); col.addView(result)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        status.text = if (FilesHandler.hasAccess(this)) "File access: GRANTED" else "File access: NOT granted"
    }

    private fun grant() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }

    private fun test() {
        refresh()
        val dir = Environment.getExternalStorageDirectory()
        val n = dir.listFiles()?.size
        result.text = if (n == null) "Cannot list ${dir.path} (no access?)" else "${dir.path}: $n entries"
    }
}
