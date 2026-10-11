package com.example.maniek

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.Normalizer
object AppCommandHandler {
    private val APPS = mapOf(
        "youtube" to "com.google.android.youtube",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "przegladarke" to "com.android.chrome",
        "mapy" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "wiadomosci" to "com.google.android.apps.messaging",
        "ustawienia" to "com.android.settings",
        "aparat" to "com.android.camera2",
        "galerie" to "com.google.android.apps.photos",
        "kalkulator" to "com.google.android.calculator",
    )

    //commands
    private val OPEN_WORDS = listOf(
        "otworz", "wlacz", "uruchom",
        "open", "launch", "start"
    )
    private val CLOSE_WORDS = listOf(
        "zamknij", "wylacz", "zakoncz",
        "close", "quit", "exit"
    )

    private fun stripDiacritics(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace("\\p{M}".toRegex(), "")

    fun handle(context: Context, heard: String) {
        val text = stripDiacritics(heard.lowercase())

        val isOpen = OPEN_WORDS.any { text.contains(it) }
        val isClose = CLOSE_WORDS.any { text.contains(it) }
        val app = APPS.entries.firstOrNull { (name, _) -> text.contains(stripDiacritics(name)) }

        if (app == null || (!isOpen && !isClose)) { return }

        val ok = if (isOpen) openApp(context, app.value) else goHome(context)
    }

    private fun openApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun goHome(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) { false }
    }
}