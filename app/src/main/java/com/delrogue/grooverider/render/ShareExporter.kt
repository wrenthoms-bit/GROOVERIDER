package com.delrogue.grooverider.render

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Android share sheet -> whatever the user already uses to reach the Mac mini (spec 6.5). */
object ShareExporter {
    fun share(context: Context, file: File, mimeType: String = "audio/wav", chooserTitle: String = "Send render") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
