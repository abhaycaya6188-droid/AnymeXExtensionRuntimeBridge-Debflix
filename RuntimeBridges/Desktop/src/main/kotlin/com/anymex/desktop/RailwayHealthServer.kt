package com.anymex.desktop

import fi.iki.elonen.NanoHTTPD
import com.google.gson.Gson

class RailwayHealthServer(port: Int) : NanoHTTPD(port) {
    private val gson = Gson()
    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/", "/health" -> newFixedLengthResponse(
            Response.Status.OK, "application/json",
            gson.toJson(mapOf("ok" to true, "service" to "debflix-cs-runtime", "mode" to "health-only"))
        ).apply { addHeader("Cache-Control", "no-store") }
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not_found"}""")
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    com.anymex.desktop.cloudstream.CloudStreamExtensionLoader.initialize()
    RailwayHealthServer(port).apply {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        System.err.println("Debflix CS Railway health server listening on port $port")
    }
}
