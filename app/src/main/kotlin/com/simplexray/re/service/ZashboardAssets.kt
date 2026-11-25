package com.simplexray.re.service

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Only handles public dashboard files; a null result must go through API authentication. */
internal class ZashboardAssets(private val openAsset: (String) -> InputStream) {
    fun serve(method: Method, uri: String): Response? {
        if (method != Method.GET && method != Method.HEAD) return null

        val response = when {
            uri == "/" || uri == "/ui" -> textResponse(Response.Status.FOUND, "").apply {
                addHeader("Location", "/ui/")
            }
            !uri.startsWith("/ui/") -> return null
            else -> {
                val path = uri.removePrefix("/ui/").ifEmpty { "index.html" }
                // NanoHTTPD has already URL-decoded session.uri once. Reject remaining '%' too,
                // so double-encoded separators/traversal can never reach AssetManager.
                if (path.contains("..") || path.any { it == '\\' || it == '%' || it.code < 32 || it.code == 127 } ||
                    path.split('/').any { it.isEmpty() || it == "." }
                ) {
                    textResponse(Response.Status.BAD_REQUEST, "Invalid dashboard path.")
                } else {
                    assetResponse(method, path)
                }
            }
        }
        // NanoHTTPD 2.3.1 still writes fixed-length bodies on HEAD. Preserve headers, not data.
        if (method == Method.HEAD) {
            response.data.close()
            response.data = ByteArrayInputStream(ByteArray(0))
        }
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun assetResponse(method: Method, path: String): Response {
        val response = try {
            val stream = openAsset("zashboard/$path")
            if (method == Method.HEAD) {
                stream.use {
                    // AssetInputStream.available() gives the remaining APK asset length,
                    // including compressed assets; HEAD need not read the file contents.
                    NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK, contentType(path), ByteArrayInputStream(ByteArray(0)), it.available().toLong(),
                    )
                }
            } else {
                // Unknown-length streams use the InputStream overload, not a String response.
                NanoHTTPD.newChunkedResponse(Response.Status.OK, contentType(path), stream)
            }
        } catch (_: IOException) {
            return textResponse(Response.Status.NOT_FOUND, "Bundled dashboard file not found.")
        }
        response.addHeader("Cache-Control", if (path.startsWith("assets/")) {
            "public, max-age=31536000, immutable"
        } else {
            "no-cache"
        })
        return response
    }

    private fun textResponse(status: Response.Status, text: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", text).apply {
            addHeader("Cache-Control", "no-cache")
        }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "svg" -> "image/svg+xml; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "md", "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
