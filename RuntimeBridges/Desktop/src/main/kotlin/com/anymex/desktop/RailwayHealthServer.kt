package com.anymex.desktop

import fi.iki.elonen.NanoHTTPD
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking

class RailwayHealthServer(port: Int) : NanoHTTPD(port) {
    private val gson = Gson()
    private val token = System.getenv("CS_BRIDGE_TOKEN")?.trim().orEmpty()
    private val allowedSources = System.getenv("CS_ALLOWED_SOURCE_IDS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    private val allowedMethods = setOf("csSearch", "csGetDetail")

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/" || session.uri == "/health") {
            val loadedKeys = com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.loadedMap.keys().toList().sorted()
            return json(Response.Status.OK, mapOf(
                "ok" to true,
                "service" to "debflix-cs-runtime",
                "mode" to "controlled-rpc",
                "rpcEnabled" to (token.isNotEmpty() && allowedSources.isNotEmpty()),
                "loadedCount" to loadedKeys.size,
                "loadedProviders" to loadedKeys
            ))
        }
        if (session.uri == "/diagnostics") {
            val loaded = com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.loadedMap.entries.map { (k, v) ->
                mapOf("id" to k, "name" to v.name, "lang" to v.lang, "mainUrl" to v.mainUrl)
            }
            return json(Response.Status.OK, mapOf(
                "ok" to true,
                "count" to loaded.size,
                "providers" to loaded,
                "allowedSources" to allowedSources.toList().sorted()
            ))
        }
        if (session.uri != "/rpc" || session.method != Method.POST) return json(Response.Status.NOT_FOUND, mapOf("error" to "not_found"))
        if (token.isEmpty() || allowedSources.isEmpty()) return json(Response.Status.SERVICE_UNAVAILABLE, mapOf("error" to "rpc_not_configured"))
        if (session.headers["authorization"].orEmpty() != "Bearer $token") return json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            if (body.length > 64 * 1024) return json(Response.Status.BAD_REQUEST, mapOf("error" to "request_too_large"))
            val req = gson.fromJson(body, JsonObject::class.java)
            val method = req.get("method")?.asString.orEmpty()
            val args = req.getAsJsonObject("args") ?: JsonObject()
            val sourceId = args.get("sourceId")?.asString.orEmpty()
            if (method !in allowedMethods) return json(Response.Status.FORBIDDEN, mapOf("error" to "method_not_allowed"))
            if (sourceId !in allowedSources) return json(Response.Status.FORBIDDEN, mapOf("error" to "source_not_allowed"))

            val data = runBlocking {
                when (method) {
                    "csSearch" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.search(sourceId, args.get("query")?.asString.orEmpty(), args.get("page")?.asInt ?: 1)
                    "csGetDetail" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchDetails(sourceId, args.get("url")?.asString.orEmpty())
                    else -> "{}"
                }
            }
            json(Response.Status.OK, mapOf("ok" to true, "data" to gson.fromJson(data, Any::class.java)))
        } catch (ex: Exception) {
            System.err.println("[HTTP-RPC] " + ex.javaClass.simpleName + ": " + ex.message)
            json(Response.Status.INTERNAL_ERROR, mapOf("error" to "rpc_failed"))
        }
    }

    private fun json(status: Response.Status, body: Any): Response = newFixedLengthResponse(status, "application/json", gson.toJson(body)).apply { addHeader("Cache-Control", "no-store") }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    RailwayHealthServer(port).apply {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        System.err.println("Debflix CS Railway server listening on port " + port)
    }
    com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.initialize()
    val extDir = System.getenv("CS_EXTENSIONS_DIR")?.trim().orEmpty()
    if (extDir.isNotEmpty() && java.io.File(extDir).isDirectory) {
        Thread {
            try {
                System.err.println("Loading extensions from $extDir...")
                runBlocking {
                    com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.loadExtensions(extDir)
                }
                System.err.println("Loaded " + com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.loadedMap.size + " extension(s).")
            } catch (e: Exception) {
                System.err.println("Failed loading extensions from $extDir: ${e.message}")
            }
        }.apply {
            isDaemon = false
            start()
        }
    }
}
