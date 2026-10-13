package dev.andrompv

import android.content.Context
import java.io.File

internal object FileFonts {
    fun directory(context: Context): String {
        val directory = File(context.filesDir, "fonts").apply { mkdirs() }
        val target = File(directory, "Roboto.ttf")
        if (!target.exists()) {
            val source = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "RobotoFlex-Regular.ttf")
                .map { File("/system/fonts", it) }.firstOrNull(File::isFile)
            source?.copyTo(target, overwrite = true)
        }
        return directory.absolutePath
    }
}
