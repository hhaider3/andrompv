package dev.andrompv

import android.content.Context
import java.io.File

internal object FileFonts {
    data class Install(val directory: String, val family: String)

    @Synchronized
    fun install(context: Context): Install {
        val directory = File(context.filesDir, "fonts").apply { mkdirs() }
        val marker = File(directory, "family.txt")
        val installed = directory.listFiles()?.firstOrNull {
            it.isFile && it != marker && it.length() > 0L && it.extension.lowercase() in setOf("ttf", "otf")
        }
        if (installed != null && marker.isFile) {
            val family = marker.readText().trim()
            if (family.isNotEmpty()) return Install(directory.absolutePath, family)
        }
        val system = File("/system/fonts")
        val choices = listOf(
            "Roboto-Regular.ttf" to "Roboto",
            "RobotoStatic-Regular.ttf" to "Roboto",
            "RobotoFlex-Regular.ttf" to "Roboto",
            "NotoSans-Regular.ttf" to "Noto Sans",
            "DroidSans.ttf" to "Droid Sans",
            "NotoSerif-Regular.ttf" to "Noto Serif"
        )
        val match = choices.firstNotNullOfOrNull { (name, family) ->
            findFont(system, name)?.let { it to family }
        } ?: system.listFiles()?.firstOrNull {
            it.isFile && it.length() > 0L && it.extension.lowercase() in setOf("ttf", "otf")
        }?.let { it to "sans-serif" }
        if (match != null) {
            val (source, family) = match
            val target = File(directory, source.name)
            val copied = runCatching {
                if (!target.exists() || target.length() == 0L) source.copyTo(target, overwrite = true)
                marker.writeText(family)
            }.isSuccess
            if (copied && target.isFile && target.length() > 0L) return Install(directory.absolutePath, family)
        }
        return Install(directory.absolutePath, "Roboto")
    }

    private fun findFont(directory: File, name: String): File? {
        val direct = File(directory, name)
        if (direct.isFile && direct.length() > 0L) return direct
        return directory.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, true) && it.length() > 0L }
    }
}
