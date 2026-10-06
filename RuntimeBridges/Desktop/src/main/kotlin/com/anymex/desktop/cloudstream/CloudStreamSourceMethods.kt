package com.anymex.desktop.cloudstream

import com.lagradost.cloudstream3.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private const val TAG = "CloudStreamMethods"

private fun Any?.toRawMap(): Map<String, Any?>? {
    if (this == null) return null
    return try {
        val mapper = com.lagradost.cloudstream3.APIHolder.mapper
        val json = mapper.writeValueAsString(this)
        mapper.readValue(
            json,
            object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
        )
    } catch (e: Exception) {
        null
    }
}

private fun <T : Any> T?.withExtraData(
    topLevel: Map<String, Any?>,
    usedKeys: Set<String>,
    additionalExtra: Map<String, Any?> = emptyMap()
): Map<String, Any?> {
    if (this == null) return topLevel.toRawMap() ?: topLevel
    val raw = this.toRawMap() ?: emptyMap()
    val extra = raw.filterKeys { it !in usedKeys } + additionalExtra
    val composite = topLevel + mapOf("extraData" to extra)
    return composite.toRawMap() ?: composite
}

private fun isInvalidData(data: String): Boolean {
    return data.isEmpty() || data == "[]" || data == "about:blank"
}

class CloudStreamSourceMethods(val provider: MainAPI) {

    suspend fun search(query: String, page: Int): Map<String, Any?> {
        System.err.println("[CS-Methods] Searching on '${provider.name}' for '$query' (page $page)...")
        return try {
            val res = provider.search(query, page)
            if (res == null) {
                System.err.println("[CS-Methods] WARNING: '${provider.name}' returned null search results.")
                return mapOf("list" to emptyList<Any>(), "hasNextPage" to false)
            }
            if (res.items.isEmpty()) {
                System.err.println("[CS-Methods] WARNING: '${provider.name}' search returned 0 items.")
            } else {
                System.err.println("[CS-Methods] '${provider.name}' search returned ${res.items.size} items (hasNext = ${res.hasNext}).")
            }
            mapOf(
                "list" to res.items.map { it.toMap() },
                "hasNextPage" to res.hasNext  
            )
        } catch (e: Exception) {
            System.err.println("[CS-Methods] ERROR: '${provider.name}' search failed: ${e.message}")
            e.printStackTrace()
            mapOf("list" to emptyList<Any>(), "hasNextPage" to false)
        }
    }

    suspend fun getDetails(url: String): Map<String, Any?> {
        System.err.println("[CS-Methods] getDetails called for '${provider.name}' with url='$url'")
        val normalizedUrl = if (provider.name.equals("CineTv", ignoreCase = true) && !url.contains(",")) "$url,1" else url
        if (provider.name.equals("CineTv", ignoreCase = true)) {
            try {
                val mapperField = provider.javaClass.getDeclaredField("mapper")
                mapperField.isAccessible = true
                val currentMapper = mapperField.get(provider) as? com.fasterxml.jackson.databind.ObjectMapper
                if (currentMapper != null) {
                    currentMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    currentMapper.setTypeFactory(currentMapper.typeFactory.withClassLoader(provider.javaClass.classLoader))
                }
            } catch (t: Throwable) {
                System.err.println("[CS-Methods] CineTv mapper setup notice: ${t.message}")
            }
        }
        val res = try {
            val r = provider.load(normalizedUrl)
            if (r == null) {
                System.err.println("[CS-Methods] WARNING: '${provider.name}' provider.load returned null for '$normalizedUrl'")
            } else {
                System.err.println("[CS-Methods] '${provider.name}' provider.load succeeded: ${r.name}")
            }
            r
        } catch (e: Throwable) {
            System.err.println("[CS-Methods] ERROR: '${provider.name}' provider.load threw: ${e.message}")
            e.printStackTrace()
            null
        }

        if (res == null && provider.name.equals("CineTv", ignoreCase = true)) {
            val clean = normalizedUrl.substringAfterLast("/")
            val vodId = clean.substringBefore(",")
            val audioType = clean.substringAfter(",", "1").toIntOrNull() ?: 1
            val direct = fetchCineTvDirect(vodId, audioType)
            if (direct != null) {
                System.err.println("[CS-Methods] CineTv direct fetch succeeded with ${(direct["episodes"] as? List<*>)?.size ?: 0} episodes")
                return direct
            }
        }

        if (res == null) return mapOf(
            "title" to null, "url" to url, "cover" to null,
            "description" to null, "episodes" to emptyList<Any>()
        )

        val episodes: List<Map<String, Any?>> = when (res) {
            is TvSeriesLoadResponse -> res.episodes.mapIndexed { i, ep -> episodeToMap(ep, i + 1) }
            is AnimeLoadResponse -> {
                res.episodes.flatMap { (key, epList) ->
                    epList.map { it to key }
                }
                .distinctBy { it.first.data }
                .mapIndexed { i, (ep, key) -> 
                    episodeToMap(ep, i + 1, mapOf("episodeGroup" to key)) 
                }
            }
            is MovieLoadResponse -> listOf(
                mapOf(
                    "name" to res.name,
                    "url" to res.dataUrl,  
                    "episodeNumber" to 1.0,
                    "thumbnail" to res.posterUrl,
                    "description" to null,
                    "dateUpload" to null,
                    "scanlator" to null,
                    "filler" to false,
                ).withExtraData(res.toRawMap()!!, setOf("name", "dataUrl", "posterUrl"))
            )
            else -> emptyList()
        }

        if (episodes.isEmpty() && provider.name.equals("CineTv", ignoreCase = true)) {
            val clean = normalizedUrl.substringAfterLast("/")
            val vodId = clean.substringBefore(",")
            val audioType = clean.substringAfter(",", "1").toIntOrNull() ?: 1
            val direct = fetchCineTvDirect(vodId, audioType)
            if (direct != null) {
                System.err.println("[CS-Methods] CineTv direct fetch fallback succeeded with ${(direct["episodes"] as? List<*>)?.size ?: 0} episodes")
                return direct
            }
        }

        val topLevel = mapOf(
            "title" to res.name,                
            "url" to res.url,
            "cover" to res.posterUrl,           
            "description" to res.plot,          
            "author" to null,
            "artist" to null,
            "genre" to (res.tags ?: emptyList()),
            "episodes" to episodes
        )
        val usedKeys = setOf("name", "url", "posterUrl", "plot", "tags")
        return res.withExtraData(topLevel, usedKeys)
    }

    suspend fun loadLinks(data: String): List<Map<String, Any?>> {
        System.err.println("[CS-Methods] loadLinks called for '${provider.name}' with data='$data'")
        val effectiveData = if (provider.name.equals("CineTv", ignoreCase = true) && !data.contains("|")) {
            val clean = data.substringAfterLast("/")
            if (clean.contains(",")) clean.replace(",", "|") else "$clean|1"
        } else {
            data
        }
        if (isInvalidData(effectiveData)) {
            Log.w(TAG, "isInvalidData returned true for: $effectiveData")
            return emptyList()
        }

        if (provider.name.equals("CineTv", ignoreCase = true)) {
            if (effectiveData.contains(".e6r4r1.com")) {
                val signed = signCineTvVideoUrl(effectiveData)
                return listOf(mapOf(
                    "url" to signed,
                    "name" to "CineTV Direct",
                    "quality" to "1080p",
                    "isM3u8" to false,
                    "isDash" to false,
                    "headers" to mapOf("User-Agent" to "okhttp/4.11.0")
                ))
            } else {
                val clean = effectiveData.substringAfterLast("/")
                val vodId = if (clean.contains("|")) clean.substringBefore("|") else clean.substringBefore(",")
                val audioType = (if (clean.contains("|")) clean.substringAfter("|") else clean.substringAfter(",", "1")).toIntOrNull() ?: 1
                val direct = fetchCineTvDirect(vodId, audioType)
                val eps = direct?.get("episodes") as? List<Map<String, Any?>>
                val epUrl = eps?.firstOrNull()?.get("url") as? String
                if (epUrl != null) {
                    return listOf(mapOf(
                        "url" to epUrl,
                        "name" to "CineTV Direct",
                        "quality" to "1080p",
                        "isM3u8" to false,
                        "isDash" to false,
                        "headers" to mapOf("User-Agent" to "okhttp/4.11.0")
                    ))
                }
            }
        }

        val links = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val subtitles = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()

        try {
            provider.loadLinks(
                effectiveData,
                false,
                { subtitle ->
                    try {
                    subtitles.add(
                        subtitle.withExtraData(
                            mapOf(
                                "file" to subtitle.url,
                                "label" to subtitle.lang
                            ),
                            setOf("url", "lang")
                        )
                    )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to map subtitle: ${subtitle.url}", e)
                    }
                },
                { link ->
                    Log.d(TAG, "Link found: ${link.url}")
                    links.add(linkToMap(link, subtitles.toList()))
                }
            )
        } catch (e: Exception) {
            val isJsonError = e.javaClass.name.contains("JsonParseException") || 
                             e.message?.contains("Unrecognized token") == true ||
                             e.message?.contains("was expecting") == true
            
            if (isJsonError && data.startsWith("http")) {
                val wrapped = "[{\"source\":\"$data\"}]"
                Log.i(TAG, "Retrying loadLinks with wrapped JSON: $wrapped")
                try {
                    provider.loadLinks(
                        wrapped,
                        false,
                        { subtitle ->
                            try {
                            subtitles.add(
                                subtitle.withExtraData(
                                    mapOf(
                                        "file" to subtitle.url,
                                        "label" to subtitle.lang
                                    ),
                                    setOf("url", "lang")
                                )
                            )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to map subtitle (wrapped): ${subtitle.url}", e)
                            }
                        },
                        { link ->
                            Log.d(TAG, "Link found (wrapped): ${link.url}")
                            links.add(linkToMap(link, subtitles.toList()))
                        }
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Wrapped retry failed", e2)
                }
            } else {
                Log.e(TAG, "loadLinks failed for $data", e)
            }
        }

        if (links.isEmpty() && data.startsWith("http") && !data.contains("[{") && !data.contains("{\"")) {
            Log.i(TAG, "Smart Fallback: Calling provider.load($data)")
            try {
                val res = provider.load(data)
                val extractedData = when (res) {
                    is MovieLoadResponse -> res.dataUrl
                    is TvSeriesLoadResponse -> res.episodes.firstOrNull()?.data
                    is AnimeLoadResponse -> res.episodes.values.firstOrNull()?.firstOrNull()?.data
                    else -> null
                }

                if (extractedData != null && !isInvalidData(extractedData) && extractedData != data) {
                    Log.i(TAG, "Smart Fallback successful, found dataUrl: $extractedData. Retrying loadLinks...")
                    provider.loadLinks(
                        extractedData,
                        false,
                        { subtitle ->
                            subtitles.add(
                                subtitle.withExtraData(
                                    mapOf("file" to subtitle.url, "label" to subtitle.lang),
                                    setOf("url", "lang")
                                )
                            )
                        },
                        { link ->
                            links.add(linkToMap(link, subtitles.toList()))
                        }
                    )
                } else if (data.startsWith("http")) {
                    Log.i(TAG, "Final resort: direct loadExtractor for: $data")
                    loadExtractor(data, "", { }, { link ->
                        links.add(linkToMap(link, emptyList()))
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Smart Fallback failed for $data", e)
            }
        }

        Log.d(TAG, "loadLinks returning ${links.size} links")
        return links
    }

    suspend fun loadLinksStream(data: String, onLinkFound: (Map<String, Any?>) -> Unit) {
        Log.d(TAG, "loadLinksStream called with data: $data")
        if (isInvalidData(data)) {
            Log.w(TAG, "isInvalidData returned true for: $data")
            return
        }
        val subtitles = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val linksFound = java.util.concurrent.atomic.AtomicBoolean(false)

        try {
            provider.loadLinks(
                data,
                false,
                { subtitle ->
                    Log.d(TAG, "Subtitle found (stream): ${subtitle.url}")
                    subtitles.add(
                        subtitle.withExtraData(
                            mapOf(
                                "file" to subtitle.url,
                                "label" to subtitle.lang
                            ),
                            setOf("url", "lang")
                        )
                    )
                },
                { link ->
                    Log.d(TAG, "Link found (stream): ${link.url}")
                    linksFound.set(true)
                    onLinkFound(linkToMap(link, subtitles.toList()))
                }
            )
        } catch (e: Exception) {
            val isJsonError = e.javaClass.name.contains("JsonParseException") || 
                             e.message?.contains("Unrecognized token") == true ||
                             e.message?.contains("was expecting") == true

            if (isJsonError && data.startsWith("http")) {
                val wrapped = "[{\"source\":\"$data\"}]"
                Log.i(TAG, "Retrying loadLinksStream with wrapped JSON: $wrapped")
                try {
                    provider.loadLinks(
                        wrapped,
                        false,
                        { subtitle ->
                            Log.d(TAG, "Subtitle found (stream-wrapped): ${subtitle.url}")
                            subtitles.add(
                                subtitle.withExtraData(
                                    mapOf(
                                        "file" to subtitle.url,
                                        "label" to subtitle.lang
                                    ),
                                    setOf("url", "lang")
                                )
                            )
                        },
                        { link ->
                            Log.d(TAG, "Link found (stream-wrapped): ${link.url}")
                            linksFound.set(true)
                            onLinkFound(linkToMap(link, subtitles.toList()))
                        }
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Wrapped retry (stream) failed", e2)
                }
            } else {
                Log.e(TAG, "loadLinksStream failed for $data", e)
            }
        }

        if (!linksFound.get() && data.startsWith("http") && !data.contains("[{") && !data.contains("{\"")) {
            Log.i(TAG, "Smart Fallback (stream): Calling provider.load($data)")
            try {
                val res = provider.load(data)
                val extractedData = when (res) {
                    is MovieLoadResponse -> res.dataUrl
                    is TvSeriesLoadResponse -> res.episodes.firstOrNull()?.data
                    is AnimeLoadResponse -> res.episodes.values.firstOrNull()?.firstOrNull()?.data
                    else -> null
                }

                if (extractedData != null && !isInvalidData(extractedData) && extractedData != data) {
                    Log.i(TAG, "Smart Fallback (stream) successful, found dataUrl: $extractedData. Retrying loadLinks...")
                    provider.loadLinks(
                        extractedData,
                        false,
                        { subtitle ->
                            subtitles.add(
                                subtitle.withExtraData(
                                    mapOf("file" to subtitle.url, "label" to subtitle.lang),
                                    setOf("url", "lang")
                                )
                            )
                        },
                        { link ->
                            linksFound.set(true)
                            onLinkFound(linkToMap(link, subtitles.toList()))
                        }
                    )
                } else if (data.startsWith("http")) {
                    Log.i(TAG, "Final resort (stream): direct loadExtractor for: $data")
                    loadExtractor(data, "", { }, { link ->
                        linksFound.set(true)
                        onLinkFound(linkToMap(link, emptyList()))
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Smart Fallback (stream) failed for $data", e)
            }
        }

        Log.d(TAG, "loadLinksStream finished for $data")
    }

    private fun linkToMap(link: ExtractorLink, subtitles: List<Map<String, Any?>>): Map<String, Any?> {
        val finalHeaders = fixHeaders(link.headers, link.referer)
        val qLabel = qualityLabel(link.quality)
        val baseMap = mutableMapOf<String, Any?>(
            "url" to link.url,
            "title" to if (qLabel.isEmpty()) link.name else "${link.name} ($qLabel)",
            "quality" to qLabel,
            "headers" to finalHeaders,
            "isM3u8" to (link.type == ExtractorLinkType.M3U8),
            "subtitles" to subtitles,
            "name" to link.name,
            "referer" to link.referer,
            "qualityInt" to link.quality,
            "extractorData" to link.extractorData,
            "type" to link.type.ordinal,
            "source" to link.source,
            "isDash" to (link.type == ExtractorLinkType.DASH),
            "audioTracks" to link.audioTracks.map { mapOf("file" to it.url) }
        )

        val usedKeys = mutableSetOf(
            "url", "name", "quality", "headers", "type", "referer", 
            "extractorData", "source", "audioTracks"
        )

        if (link is DrmExtractorLink) {
            baseMap["kid"] = link.kid
            baseMap["key"] = link.key
            baseMap["uuid"] = link.uuid.toString()
            baseMap["kty"] = link.kty
            baseMap["keyRequestParameters"] = link.keyRequestParameters
            baseMap["licenseUrl"] = link.licenseUrl
            usedKeys.addAll(listOf("kid", "key", "uuid", "kty", "keyRequestParameters", "licenseUrl"))
        }

        return link.withExtraData(baseMap, usedKeys)
    }

    private fun fixHeaders(headers: Map<String, String>?, linkReferer: String?): Map<String, String> {
        val res = headers?.toMutableMap() ?: mutableMapOf()

        if (res.none { it.key.equals("Referer", ignoreCase = true) } && !linkReferer.isNullOrBlank()) {
            res["Referer"] = linkReferer
        }

        val refererKey = res.keys.find { it.equals("Referer", ignoreCase = true) }
        val referer = refererKey?.let { res[it] }

        if (!referer.isNullOrBlank() && res.none { it.key.equals("Origin", ignoreCase = true) }) {
            try {
                val u = java.net.URI(referer)
                res["Origin"] = "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
            } catch (e: Exception) {
            }
        }

        return res
    }

    private fun qualityLabel(quality: Int): String = when {
        quality <= 0 || quality == 400 -> ""
        quality >= 2160 -> "4K"
        quality >= 1080 -> "1080p"
        quality >= 720 -> "720p"
        quality >= 480 -> "480p"
        quality >= 360 -> "360p"
        else -> "${quality}p"
    }

    private fun md5Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun aesDecryptCineTv(base64Text: String): String {
        val key = javax.crypto.spec.SecretKeySpec("0123456789123456".toByteArray(Charsets.UTF_8), "AES")
        val iv = javax.crypto.spec.IvParameterSpec("2015030120123456".toByteArray(Charsets.UTF_8))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, iv)
        val decoded = java.util.Base64.getDecoder().decode(base64Text.trim())
        val decrypted = cipher.doFinal(decoded)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun signCineTvVideoUrl(rawUrl: String): String {
        return try {
            val uri = java.net.URI(rawUrl)
            val path = uri.path
            val wsTime = java.lang.Long.toHexString(System.currentTimeMillis() / 1000 + 60)
            val wsSecret = md5Hex("00b5f05c40b4f1d91dbc9b3fd8a059ef" + path + wsTime)
            val sep = if (rawUrl.contains("?")) "&" else "?"
            "${rawUrl}${sep}wsSecret=${wsSecret}&wsTime=${wsTime}"
        } catch (e: Exception) {
            rawUrl
        }
    }

    private fun fetchCineTvDirect(vodId: String, audioType: Int): Map<String, Any?>? {
        try {
            val curTime = System.currentTimeMillis().toString()
            val deviceId = "1a2b3c4d5e6f7a8b"

            var token = cineTvTokenCache
            if (token.isNullOrEmpty()) {
                val initSign = md5Hex("47Q8tBqO4YqrMHf4" + deviceId + curTime).uppercase()
                val initHeaders = mapOf(
                    "Accept-Encoding" to "identity",
                    "androidid" to deviceId,
                    "app_id" to "filmin",
                    "app_language" to "en",
                    "channel_code" to "filmin_sh_1000",
                    "Connection" to "Keep-Alive",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "cur_time" to curTime,
                    "device_id" to deviceId,
                    "display" to "0",
                    "gaid" to "",
                    "Host" to "filmin.ajfysu.com",
                    "is_display" to "GMT+05:30",
                    "is_language" to "en",
                    "is_vvv" to "0",
                    "log-header" to "I am the log request header.",
                    "mob_mfr" to "Google",
                    "mobmodel" to "Pixel 6",
                    "package_name" to "com.dramarush.shortin",
                    "sign" to initSign,
                    "sys_platform" to "2",
                    "sysrelease" to "13",
                    "token" to "",
                    "User-Agent" to "okhttp/4.11.0",
                    "version" to "30000"
                )
                val initBody = okhttp3.FormBody.Builder()
                    .add("invited_by", "")
                    .add("is_install", "1")
                    .build()
                val initReq = okhttp3.Request.Builder()
                    .url("https://filmin.ajfysu.com/api/public/init")
                    .apply { initHeaders.forEach { (k, v) -> addHeader(k, v) } }
                    .post(initBody)
                    .build()
                val initResp = app.baseClient.newCall(initReq).execute()
                if (initResp.isSuccessful) {
                    val bodyStr = initResp.body?.string().orEmpty()
                    val dec = aesDecryptCineTv(bodyStr)
                    val json = com.google.gson.JsonParser.parseString(dec).asJsonObject
                    token = json.getAsJsonObject("result")?.getAsJsonObject("user_info")?.get("token")?.asString.orEmpty()
                    if (!token.isNullOrEmpty()) cineTvTokenCache = token
                }
            }

            val vodCurTime = System.currentTimeMillis().toString()
            val headerSign = md5Hex("47Q8tBqO4YqrMHf4" + deviceId + vodCurTime).uppercase()
            val bodySign = md5Hex("Zox882LYjEn4Rqpa" + deviceId + vodId + vodCurTime).uppercase()
            val headers = mapOf(
                "Accept-Encoding" to "identity",
                "androidid" to deviceId,
                "app_id" to "filmin",
                "app_language" to "en",
                "channel_code" to "filmin_sh_1000",
                "Connection" to "Keep-Alive",
                "Content-Type" to "application/x-www-form-urlencoded",
                "cur_time" to vodCurTime,
                "device_id" to deviceId,
                "display" to "0",
                "gaid" to "",
                "Host" to "filmin.ajfysu.com",
                "is_display" to "GMT+05:30",
                "is_language" to "en",
                "is_vvv" to "0",
                "log-header" to "I am the log request header.",
                "mob_mfr" to "Google",
                "mobmodel" to "Pixel 6",
                "package_name" to "com.dramarush.shortin",
                "sign" to headerSign,
                "sys_platform" to "2",
                "sysrelease" to "13",
                "token" to (token ?: ""),
                "User-Agent" to "okhttp/4.11.0",
                "version" to "30000"
            )
            val formBody = okhttp3.FormBody.Builder()
                .add("sign", bodySign)
                .add("vod_id", vodId)
                .add("cur_time", vodCurTime)
                .add("audio_type", audioType.toString())
                .build()
            val req = okhttp3.Request.Builder()
                .url("https://filmin.ajfysu.com/api/vod/info_new")
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .post(formBody)
                .build()
            val resp = app.baseClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string().orEmpty()
            val dec = aesDecryptCineTv(bodyStr)
            val json = com.google.gson.JsonParser.parseString(dec).asJsonObject
            val result = json.getAsJsonObject("result") ?: return null
            val title = result.get("vod_name")?.asString.orEmpty()
            val pic = result.get("vod_pic")?.asString.orEmpty()
            val plot = result.get("vod_blurb")?.asString.orEmpty()
            val collection = result.getAsJsonArray("vod_collection") ?: com.google.gson.JsonArray()

            val epList = mutableListOf<Map<String, Any?>>()
            for (i in 0 until collection.size()) {
                val item = collection.get(i).asJsonObject
                val collNum = item.get("collection")?.asInt ?: (i + 1)
                val rawVodUrl = item.get("vod_url")?.asString.orEmpty()
                val signedUrl = signCineTvVideoUrl(rawVodUrl)
                epList.add(mapOf(
                    "name" to (item.get("title")?.asString ?: "Episode $collNum"),
                    "url" to signedUrl,
                    "data" to signedUrl,
                    "dataUrl" to signedUrl,
                    "episodeNumber" to collNum.toDouble(),
                    "thumbnail" to pic,
                    "description" to null
                ))
            }

            return mapOf(
                "title" to title,
                "url" to "https://filmin.ajfysu.com/$vodId,$audioType",
                "cover" to pic,
                "description" to plot,
                "author" to null,
                "artist" to null,
                "genre" to (result.get("vod_tag")?.asString?.split("/") ?: emptyList()),
                "episodes" to epList
            )
        } catch (e: Throwable) {
            System.err.println("[CS-Methods] CineTv direct fetch error: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    companion object {
        @Volatile
        private var cineTvTokenCache: String? = null
    }
}


fun SearchResponse.toMap(): Map<String, Any?> {
    val topLevel = mapOf(
        "title" to name,          
        "url" to url,
        "apiName" to apiName,
        "cover" to posterUrl,     
        "type" to type?.ordinal,
        "id" to id,
        "quality" to quality?.ordinal,
        "score" to score?.toInt(100)
    )
    val usedKeys = setOf("name", "url", "apiName", "posterUrl", "type", "id", "quality", "score")
    return this.withExtraData(topLevel, usedKeys)
}

fun episodeToMap(
    ep: Episode, 
    fallbackNumber: Int, 
    additionalExtra: Map<String, Any?> = emptyMap()
): Map<String, Any?> {
    val rawNum = ep.episode?.toDouble() ?: fallbackNumber.toDouble()
    val topLevel = mapOf(
        "name" to ep.name,
        "url" to ep.data,              
        "episodeNumber" to rawNum,     
        "thumbnail" to ep.posterUrl,
        "description" to ep.description,
        "dateUpload" to ep.date?.toString(),
        "scanlator" to null,
        "filler" to false,
    )
    val usedKeys = setOf("name", "data", "episode", "posterUrl", "description", "date")
    return ep.withExtraData(topLevel, usedKeys, additionalExtra)
}
