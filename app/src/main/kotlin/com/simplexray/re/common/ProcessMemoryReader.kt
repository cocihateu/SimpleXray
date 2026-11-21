package com.simplexray.re.common

import java.io.File

object ProcessMemoryReader {
    // VmRSS includes resident anonymous, file-backed, and shared memory.
    fun readRssBytes(pid: Int): Long? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.getOrNull()
}
