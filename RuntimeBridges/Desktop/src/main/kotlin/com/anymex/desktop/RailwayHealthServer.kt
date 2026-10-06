package com.anymex.desktop

import fi.iki.elonen.NanoHTTPD
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DiagnosticsLog {
    val startupLogs = java.util.concurrent.CopyOnWriteArrayList<String>()
    val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
}

class RailwayHealthServer(port: Int) : NanoHTTPD(port) {
    private val gson = Gson()
    private val token = System.getenv("CS_BRIDGE_TOKEN")?.trim().orEmpty()
    private val allowedSources = System.getenv("CS_ALLOWED_SOURCE_IDS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    private val allowedMethods = setOf("csSearch", "csGetDetail", "csGetVideoList", "csGetVideoListStream")
    private data class RpcJob(
        @Volatile var status: String = "pending",
        @Volatile var data: Any? = null,
        @Volatile var error: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )
    private val rpcJobs = ConcurrentHashMap<String, RpcJob>()

    private fun authorized(session: IHTTPSession): Boolean =
        token.isNotEmpty() && allowedSources.isNotEmpty() &&
            session.headers["authorization"].orEmpty() == "Bearer $token"

    private fun cleanupJobs() {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        rpcJobs.entries.removeIf { it.value.createdAt < cutoff }
    }

    private fun executeRpc(method: String, sourceId: String, query: String, page: Int, url: String): Any? {
        val raw = runBlocking {
            when (method) {
                "csSearch" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.search(sourceId, query, page)
                "csGetDetail" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchDetails(sourceId, url)
                "csGetVideoList" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchVideoList(sourceId, url)
                "csGetVideoListStream" -> {
                    val links = java.util.Collections.synchronizedList(mutableListOf<Any?>())
                    com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchVideoListStream(sourceId, url) { linkJson ->
                        try {
                            links.add(gson.fromJson(linkJson, Any::class.java))
                        } catch (_: Throwable) {}
                    }
                    links.toList()
                }
                else -> "{}"
            }
        }
        return gson.fromJson(raw, Any::class.java)
    }

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
                "allowedSources" to allowedSources.toList().sorted(),
                "startupLogs" to DiagnosticsLog.startupLogs.take(150),
                "logs" to DiagnosticsLog.logs.takeLast(100)
            ))
        }
        if (session.uri == "/rpc/result" && session.method == Method.GET) {
            if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))
            cleanupJobs()
            val id = session.parameters["id"]?.firstOrNull().orEmpty()
            val job = rpcJobs[id] ?: return json(Response.Status.NOT_FOUND, mapOf("ok" to false, "error" to "job_not_found"))
            return when (job.status) {
                "done" -> {
                    rpcJobs.remove(id)
                    json(Response.Status.OK, mapOf("ok" to true, "status" to "done", "data" to job.data))
                }
                "error" -> {
                    rpcJobs.remove(id)
                    json(Response.Status.OK, mapOf("ok" to false, "status" to "error", "error" to (job.error ?: "rpc_failed")))
                }
                else -> json(Response.Status.OK, mapOf("ok" to true, "status" to "pending"))
            }
        }

        if (session.uri == "/rpc/start" && session.method == Method.POST) {
            if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, mapOf("error" to "unauthorized"))
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"].orEmpty()
                if (body.length > 64 * 1024) return json(Response.Status.BAD_REQUEST, mapOf("error" to "request_too_large"))
                val req = gson.fromJson(body, JsonObject::class.java)
                val method = req.get("method")?.asString.orEmpty()
                val argsElem = req.get("args")
                val argsObj = if (argsElem != null && argsElem.isJsonObject) argsElem.asJsonObject else null
                val argsArr = if (argsElem != null && argsElem.isJsonArray) argsElem.asJsonArray else null
                val sourceId = argsObj?.get("sourceId")?.asString
                    ?: (if (argsArr != null && argsArr.size() > 0) argsArr.get(0).asString else "")
                val query = argsObj?.get("query")?.asString
                    ?: (if (argsArr != null && argsArr.size() > 1) argsArr.get(1).asString else "")
                val page = argsObj?.get("page")?.asInt
                    ?: (if (argsArr != null && argsArr.size() > 2) argsArr.get(2).asInt else 1)
                val url = argsObj?.get("url")?.asString
                    ?: (if (argsArr != null && argsArr.size() > 1) argsArr.get(1).asString else "")
                if (method !in allowedMethods) return json(Response.Status.FORBIDDEN, mapOf("error" to "method_not_allowed"))
                if (sourceId !in allowedSources) return json(Response.Status.FORBIDDEN, mapOf("error" to "source_not_allowed"))
                cleanupJobs()
                val id = UUID.randomUUID().toString()
                val job = RpcJob()
                rpcJobs[id] = job
                Thread {
                    try {
                        job.data = executeRpc(method, sourceId, query, page, url)
                        job.status = "done"
                    } catch (ex: Exception) {
                        System.err.println("[HTTP-RPC-ASYNC] " + ex.javaClass.simpleName + ": " + ex.message)
                        job.error = ex.message ?: "rpc_failed"
                        job.status = "error"
                    }
                }.apply {
                    name = "cs-rpc-$method-$sourceId"
                    isDaemon = true
                    start()
                }
                json(Response.Status.ACCEPTED, mapOf("ok" to true, "status" to "pending", "jobId" to id))
            } catch (ex: Exception) {
                System.err.println("[HTTP-RPC-START] " + ex.javaClass.simpleName + ": " + ex.message)
                json(Response.Status.INTERNAL_ERROR, mapOf("error" to "rpc_start_failed"))
            }
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
            val argsElem = req.get("args")
            val argsObj = if (argsElem != null && argsElem.isJsonObject) argsElem.asJsonObject else null
            val argsArr = if (argsElem != null && argsElem.isJsonArray) argsElem.asJsonArray else null

            val sourceId = argsObj?.get("sourceId")?.asString 
                ?: (if (argsArr != null && argsArr.size() > 0) argsArr.get(0).asString else "")
            val query = argsObj?.get("query")?.asString 
                ?: (if (argsArr != null && argsArr.size() > 1) argsArr.get(1).asString else "")
            val page = argsObj?.get("page")?.asInt 
                ?: (if (argsArr != null && argsArr.size() > 2) argsArr.get(2).asInt else 1)
            val url = argsObj?.get("url")?.asString 
                ?: (if (argsArr != null && argsArr.size() > 1) argsArr.get(1).asString else "")

            if (method !in allowedMethods) return json(Response.Status.FORBIDDEN, mapOf("error" to "method_not_allowed"))
            if (sourceId !in allowedSources) return json(Response.Status.FORBIDDEN, mapOf("error" to "source_not_allowed"))

            val data = runBlocking {
                when (method) {
                    "csSearch" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.search(sourceId, query, page)
                    "csGetDetail" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchDetails(sourceId, url)
                    "csGetVideoList" -> com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.fetchVideoList(sourceId, url)
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
    val origErr = System.err
    try {
        System.setErr(object : java.io.PrintStream(origErr) {
            override fun println(x: String?) {
                origErr.println(x)
                if (x != null) {
                    if (DiagnosticsLog.startupLogs.size < 200) DiagnosticsLog.startupLogs.add(x)
                    if (DiagnosticsLog.logs.size > 250) DiagnosticsLog.logs.removeAt(0)
                    DiagnosticsLog.logs.add(x)
                }
            }
            override fun print(x: String?) {
                origErr.print(x)
                if (x != null) {
                    if (DiagnosticsLog.startupLogs.size < 200) DiagnosticsLog.startupLogs.add(x)
                    if (DiagnosticsLog.logs.size > 250) DiagnosticsLog.logs.removeAt(0)
                    DiagnosticsLog.logs.add(x)
                }
            }
        })
    } catch (_: Exception) {}

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
