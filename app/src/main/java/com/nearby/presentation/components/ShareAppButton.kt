package com.nearby.presentation.components

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object AppSharer {

    fun shareApp(context: Context) {
        try {
            // Get the APK file
            val appInfo = context.applicationInfo
            val apkFile = File(appInfo.sourceDir)

            // Copy to a shareable location
            val shareDir = File(context.cacheDir, "share")
            shareDir.mkdirs()
            val shareFile = File(shareDir, "NearBy.apk")
            apkFile.copyTo(shareFile, overwrite = true)

            // Get URI via FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NearBy - Messaggistica Offline")
                putExtra(Intent.EXTRA_TEXT, """
                    📡 NearBy - Messaggistica P2P Offline

                    Scarica l'app per chattare senza internet!
                    Funziona via Bluetooth e WiFi Direct.

                    Installa l'APK allegato e aprilo vicino a me!
                """.trimIndent())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(shareIntent, "Condividi NearBy con...")
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareInviteLink(context: Context) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NearBy - Messaggistica Offline")
            putExtra(Intent.EXTRA_TEXT, """
                📡 Prova NearBy!

                Un'app di messaggistica che funziona SENZA INTERNET!
                Usa Bluetooth e WiFi Direct per chattare con chi è vicino a te.

                🔒 Crittografia end-to-end
                📴 Funziona in modalità aereo
                🔗 Connessione diretta P2P

                Scarica l'app e aprila vicino a me per connetterci!
            """.trimIndent())
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "Invita amici su NearBy")
        )
    }
}
