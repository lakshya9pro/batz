package it.dogior.hadEnough

import com.lagradost.api.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

object DaddyLiveProxyServer {
    private var serverSocket: ServerSocket? = null
    var port: Int = 13000
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(start = true, isDaemon = true) {
            try {
                // Find a free port starting from 13000
                while (true) {
                    try {
                        serverSocket = ServerSocket(port)
                        break
                    } catch (e: Exception) {
                        port++
                        if (port > 14000) {
                            Log.d("DDLProxy", "Failed to bind to any port")
                            return@thread
                        }
                    }
                }
                Log.d("DDLProxy", "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    thread(start = true) {
                        handleConnection(socket)
                    }
                }
            } catch (e: Exception) {
                Log.d("DDLProxy", "Server error: $e")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val firstLine = reader.readLine() ?: return
            
            // Parse HTTP Request Line (e.g., GET /proxy?url=... HTTP/1.1)
            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendResponse(socket, 400, "Bad Request", "text/plain", "Invalid HTTP method".toByteArray())
                return
            }
            
            val reqUrl = parts[1]
            if (!reqUrl.startsWith("/proxy")) {
                sendResponse(socket, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                return
            }
            
            // Parse query parameters
            val query = reqUrl.substringAfter("?", "")
            val params = query.split("&").associate {
                val pair = it.split("=")
                val key = pair.getOrNull(0) ?: ""
                val value = pair.getOrNull(1) ?: ""
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
            
            val streamUrl = params["url"]
            val referer = params["referer"] ?: "https://dlhd.st/"
            
            if (streamUrl == null) {
                sendResponse(socket, 400, "Bad Request", "text/plain", "Missing url parameter".toByteArray())
                return
            }
            
            // Proxy connection
            val urlObj = URL(streamUrl)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            
            // Inject DaddyLive Anti-Hotlink headers
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
            conn.setRequestProperty("Referer", referer)
            val origin = "${urlObj.protocol}://${urlObj.host}"
            conn.setRequestProperty("Origin", origin)
            
            val responseCode = conn.responseCode
            if (responseCode >= 400) {
                sendResponse(socket, responseCode, conn.responseMessage ?: "Error", "text/plain", "Error fetching stream".toByteArray())
                return
            }
            
            val contentType = conn.contentType ?: "application/octet-stream"
            val responseData = conn.inputStream.use { it.readBytes() }
            
            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                // Modify playlist (manifest proxying)
                val text = String(responseData, Charsets.UTF_8)
                val lines = text.split("\n")
                val rewrittenLines = lines.map { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        line
                    } else {
                        // Resolve relative segment/child-playlist link to absolute link
                        val absoluteUrl = URL(URL(streamUrl), trimmed).toString()
                        if (absoluteUrl.contains(".m3u8", ignoreCase = true)) {
                            "http://127.0.0.1:$port/proxy?url=${URLEncoder.encode(absoluteUrl, "UTF-8")}&referer=${URLEncoder.encode(referer, "UTF-8")}"
                        } else {
                            // Optimisation: Skip proxying Cloudflare R2 / AWS storage links since they have signed V4 parameters
                            if (absoluteUrl.contains("cloudflarestorage.com") || absoluteUrl.contains("amazonaws.com")) {
                                absoluteUrl
                            } else {
                                "http://127.0.0.1:$port/proxy?url=${URLEncoder.encode(absoluteUrl, "UTF-8")}&referer=${URLEncoder.encode(referer, "UTF-8")}"
                            }
                        }
                    }
                }
                val bodyText = rewrittenLines.joinToString("\n")
                sendResponse(socket, 200, "OK", "application/vnd.apple.mpegurl", bodyText.toByteArray(Charsets.UTF_8))
            } else {
                // Proxy binary video TS segments
                sendResponse(socket, 200, "OK", contentType, responseData)
            }
        } catch (e: Exception) {
            Log.d("DDLProxy", "Connection handling error: $e")
            try {
                sendResponse(socket, 500, "Internal Server Error", "text/plain", e.toString().toByteArray())
            } catch (ignored: Exception) {}
        }
    }
    
    private fun sendResponse(socket: Socket, statusCode: Int, statusText: String, contentType: String, data: ByteArray) {
        try {
            socket.use { s ->
                val out = s.getOutputStream()
                val writer = out.bufferedWriter()
                writer.write("HTTP/1.1 $statusCode $statusText\r\n")
                writer.write("Content-Type: $contentType\r\n")
                writer.write("Content-Length: ${data.size}\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
                out.write(data)
                out.flush()
            }
        } catch (ignored: Exception) {}
    }
}
