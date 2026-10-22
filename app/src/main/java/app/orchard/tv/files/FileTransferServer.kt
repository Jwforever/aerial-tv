package app.orchard.tv.files

import android.content.Context
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import app.orchard.tv.wallpaper.PhoneServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * The phone side of the file manager: a tiny page served on the local network while the transfer
 * sheet is open. Uploads go to AerialDrop in shared storage, so they appear in the TV's file
 * manager and can be opened by the TV's own apps. The random path token is the only credential;
 * the server is stopped when the sheet closes.
 */
object FileTransferServer {
    private const val TAG = "AerialTransfer"
    private const val PORT = 8791
    private const val MAX_UPLOAD = 512L * 1024 * 1024

    private var running: TransferServer? = null

    fun transferDir(context: Context): File = File(Environment.getExternalStorageDirectory(), "AerialDrop")

    /** Starts the server and returns the phone URL, or null when the TV has no LAN or no writable storage. */
    @Synchronized
    fun start(context: Context): String? {
        stop()
        val dir = transferDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val ip = PhoneServer.lanAddress(context) ?: return null
        val token = newToken()
        val server = TransferServer(context.applicationContext, token, dir)
        if (runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }.isFailure) {
            runCatching { server.stop() }
            return null
        }
        running = server
        return "http://$ip:$PORT/$token/"
    }

    @Synchronized
    fun stop() {
        running?.let { runCatching { it.stop() } }
        running = null
    }

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private class TransferServer(
        private val context: Context,
        private val token: String,
        private val dir: File,
    ) : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            val prefix = "/$token"
            if (!session.uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
            val path = session.uri.removePrefix(prefix).ifEmpty { "/" }
            return runCatching {
                when {
                    path == "/" -> page()
                    path == "/api/list" -> listFiles()
                    path == "/upload" && session.method == Method.POST -> upload(session)
                    path.startsWith("/file/") -> download(path.removePrefix("/file/"))
                    else -> text(Response.Status.NOT_FOUND, "Not found")
                }
            }.getOrElse {
                Log.w(TAG, "request failed", it)
                text(Response.Status.INTERNAL_ERROR, "error")
            }
        }

        private fun page(): Response {
            val html = context.assets.open("web/files.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                addHeader("Cache-Control", "no-store")
            }
        }

        private fun listFiles(): Response {
            val files = JSONArray()
            dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    files.put(
                        JSONObject()
                            .put("name", file.name)
                            .put("size", file.length())
                            .put("modified", file.lastModified()),
                    )
                }
            return json(JSONObject().put("files", files))
        }

        private fun upload(session: IHTTPSession): Response {
            val requested = session.parameters["name"]?.firstOrNull()
            val name = safeName(requested) ?: return text(Response.Status.BAD_REQUEST, "bad name")
            val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
            if (length <= 0L) return text(Response.Status.BAD_REQUEST, "missing size")
            if (length > MAX_UPLOAD) return text(Response.Status.BAD_REQUEST, "too large")
            val target = uniqueFile(name)
            val part = File(dir, ".upload-${System.nanoTime()}.part")
            var total = 0L
            return try {
                // Do not close the session stream: NanoHTTPD owns it and closing it takes the
                // socket down before the response can be written (curl then sees an empty reply).
                val input = session.inputStream
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IllegalStateException("unexpected end of upload")
                        output.write(buffer, 0, read)
                        remaining -= read
                        total += read
                    }
                }
                if (total == 0L) {
                    part.delete()
                    return text(Response.Status.BAD_REQUEST, "empty file")
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                json(JSONObject().put("name", target.name).put("size", target.length()))
            } catch (e: Exception) {
                part.delete()
                Log.w(TAG, "upload failed", e)
                text(Response.Status.INTERNAL_ERROR, "upload failed")
            }
        }

        private fun download(rawName: String): Response {
            val name = safeName(URLDecoder.decode(rawName, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "Not found")
            val file = File(dir, name)
            if (!file.isFile || !file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                return text(Response.Status.NOT_FOUND, "Not found")
            }
            val extension = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length()).apply {
                addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            }
        }

        private fun safeName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
                .trim()
                .take(180)
            if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
            return cleaned
        }

        private fun uniqueFile(name: String): File {
            val direct = File(dir, name)
            if (!direct.exists()) return direct
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            for (index in 1..999) {
                val candidate = File(dir, "$base ($index)$extension")
                if (!candidate.exists()) return candidate
            }
            return File(dir, "$base-${System.currentTimeMillis()}$extension")
        }

        private fun json(value: JSONObject): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString()).apply {
                addHeader("Cache-Control", "no-store")
            }

        private fun text(status: Response.Status, value: String): Response =
            newFixedLengthResponse(status, "text/plain; charset=utf-8", value)
    }
}
