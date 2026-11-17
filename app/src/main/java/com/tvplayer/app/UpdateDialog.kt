package com.tvplayer.app
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView

object UpdateDialog {
    fun show(context: Context, versionCode: Int, changelog: String, onInstall: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.dialogUpdateText).text = if (changelog.isBlank()) "Новая версия" else changelog
        AlertDialog.Builder(context).setTitle("Обновление v$versionCode").setView(view).setPositiveButton("Установить") { _, _ -> onInstall() }.setNegativeButton("Позже", null).show()
    }
}
