package com.watchreader.mobile.highlight

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Hands selected text to other apps on the phone: a dictionary, a translator, the clipboard, the
 * share sheet. Each returns false when nothing on the phone could take it, for the screen to say so.
 */
object TextIntents {
    fun lookUp(context: Context, text: String, chooserTitle: String): Boolean =
        hand(context, text, HandoffChoice.lookUp(handlers(context)), chooserTitle)

    fun translate(context: Context, text: String, chooserTitle: String): Boolean =
        hand(context, text, HandoffChoice.translate(handlers(context)), chooserTitle)

    /** Puts [text] on the clipboard. */
    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Android 13 and later show their own note of a copy; before that the app says it. */
    val needsCopyConfirmation: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun share(context: Context, text: String, subject: String?, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrBlank()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        return start(context, Intent.createChooser(send, chooserTitle))
    }

    private fun processText(text: String) = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        // The page is not editable: an app that offers to replace the words is told not to.
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    private fun hand(context: Context, text: String, choice: Handoff, chooserTitle: String): Boolean = when (choice) {
        is Handoff.Open -> start(context, processText(text).setClassName(choice.handler.packageName, choice.handler.className))
        is Handoff.Choose -> {
            val chooser = Intent.createChooser(processText(text), chooserTitle)
            val leaveOut = choice.leaveOut.map { ComponentName(it.packageName, it.className) }
            if (leaveOut.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, leaveOut.toTypedArray())
            start(context, chooser)
        }
        Handoff.WebSearch -> start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))
    }

    /** The apps that take selected text; the manifest's queries element makes them visible from Android 11 on. */
    private fun handlers(context: Context): List<TextHandler> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val found: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, 0)
        }
        return found.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (activity.packageName == context.packageName) return@mapNotNull null
            TextHandler(activity.packageName, activity.name, info.loadLabel(pm)?.toString().orEmpty())
        }
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // An app may list an activity it then refuses to be started from outside.
            false
        }
    }
}
