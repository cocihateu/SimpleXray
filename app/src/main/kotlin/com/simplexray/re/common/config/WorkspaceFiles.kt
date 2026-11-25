package com.simplexray.re.common.config

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Workspace writes never truncate the only readable copy after a failed rename. */
internal object WorkspaceFiles {
    fun requireWritable(file: File, maximumVersion: Int) {
        if (!file.exists()) return
        val text = file.readText().trimStart()
        if (text.startsWith("[")) {
            JSONArray(text) // Legacy rules use a bare array; malformed data must not be overwritten.
            return
        }
        val version = JSONObject(text).optInt("version")
        if (version > maximumVersion) throw IOException("${file.name}: unsupported schema $version")
    }

    fun write(file: File, content: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
