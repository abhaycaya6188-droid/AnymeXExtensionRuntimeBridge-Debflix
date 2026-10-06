package com.anymex.desktop.cloudstream

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lagradost.cloudstream3.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.DataStore
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

object CloudStreamExtensionLoader {
    val loadedMap = ConcurrentHashMap<String, MainAPI>()
    private val classLoaders = ConcurrentHashMap<String, URLClassLoader>()
    private val scanMutex = Mutex()
    private val gson = Gson()
    private var initialized = false
    @Volatile private var cineStreamDiagnostic = "provider-order: unavailable"
    @Volatile private var cineStreamClassLoader: ClassLoader? = null
    @Volatile private var fibwatchAvailable = false
    @Volatile private var skymoviesAvailable = false

    fun getCineStreamDiagnostic(): Map<String, Any?> = mapOf(
        "summary" to cineStreamDiagnostic,
        "fibwatchAvailable" to fibwatchAvailable,
        "fibwatchProviderKey" to if (fibwatchAvailable) "p_fibwatch" else null,
        "skymoviesAvailable" to skymoviesAvailable,
        "skymoviesProviderKey" to if (skymoviesAvailable) "p_skymovies" else null,
    )

    fun initialize() {
        if (initialized) return
        
        val context = Application()
        // Match the Android runtime: CineStream reads these global stores during load().
        DataStore.init(context.applicationContext)
        AcraApplication.context = context.applicationContext
        CloudStreamApp.context = context.applicationContext

        Injekt.addSingletonFactory<Application> { context }
        Injekt.addSingletonFactory<Context> { context }
        Injekt.addSingletonFactory { NetworkHelper(context) }
        Injekt.addSingletonFactory<okhttp3.OkHttpClient> { Injekt.get<NetworkHelper>().client }
        Injekt.addSingletonFactory<Json> {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        System.setProperty("anymex.ua.movielinkbd.li", "Mozilla/5.0")
        initialized = true
        System.err.println("CloudStream Runtime initialized!")
    }

    suspend fun loadExtensions(folderPath: String): String = scanMutex.withLock {
        initialize()
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return "[]"

        val jsonArray = com.google.gson.JsonArray()

        folder.listFiles { file -> file.extension == "jar" }?.forEach { jar ->
            System.err.println("[CS] Scanning: ${jar.name}")
            
            val jarProcessThread = Thread {
                try {
                    val zipFile = ZipFile(jar)
                    val manifestEntry = zipFile.getEntry("manifest.json") ?: zipFile.getEntry("plugins.manifest")
                    if (manifestEntry == null) {
                        System.err.println("  [CS] Skipped ${jar.name}: no manifest.json or plugins.manifest found")
                        zipFile.close()
                        return@Thread
                    }

                    val manifestContent = zipFile.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                    val manifest = gson.fromJson(manifestContent, JsonObject::class.java)
                    val pluginClassName = manifest.get("pluginClassName")?.asString ?: ""
                    val version = manifest.get("version")?.asString ?: "1.0.0"
                    System.err.println("  [CS] Manifest: pluginClassName='$pluginClassName' version='$version'")
                    zipFile.close()

                    val tempJar = File.createTempFile("cs_ext_", ".jar").apply { deleteOnExit() }
                    jar.copyTo(tempJar, overwrite = true)
                    val classLoader = com.anymex.desktop.ChildFirstURLClassLoader(arrayOf(tempJar.toURI().toURL()), CloudStreamExtensionLoader::class.java.classLoader)
                    
                    val pluginClass = if (pluginClassName.isNotEmpty()) {
                        try { Class.forName(pluginClassName, false, classLoader) } catch (e: Throwable) {
                            System.err.println("  [CS] Could not load pluginClass '$pluginClassName': ${e.javaClass.simpleName}: ${e.message}")
                            null
                        }
                    } else null

                    if (pluginClass != null) {
                        try {
                            populatePlugin(pluginClass, version, pluginClassName, jsonArray)
                        } catch (e: Throwable) {
                            System.err.println("  [CS] Error initializing $pluginClassName: ${e.message}")
                        }
                    }

                    val beforeCount = jsonArray.size()
                    findMainApisInJar(jar, classLoader, version, jsonArray)
                    val afterCount = jsonArray.size()
                    if (afterCount == beforeCount && pluginClass == null) {
                        System.err.println("  [CS] No APIs found in ${jar.name}")
                    }
                    classLoaders[jar.absolutePath] = classLoader
                } catch (e: Throwable) {
                    System.err.println("  [CS] Error processing ${jar.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            
            jarProcessThread.isDaemon = true
            jarProcessThread.start()
            jarProcessThread.join(20000L)

            if (jarProcessThread.isAlive) {
                System.err.println("  [CS] Warning: JAR ${jar.name} HUNG during processing and was bypassed.")
            }
        }

        val finalJson = gson.toJson(jsonArray)
        System.err.println("[CS] Scan complete. Found ${jsonArray.size()} providers.")
        return finalJson
    }

    private fun populatePlugin(pluginClass: Class<*>, version: String, className: String, jsonArray: com.google.gson.JsonArray): Any? {
        val instance = instantiateApi(pluginClass) ?: return null
        
        if (instance is com.lagradost.cloudstream3.plugins.Plugin) {
            val preApis = com.lagradost.cloudstream3.APIHolder.apis.toList()
            val context = Injekt.get<Application>()
            val loadThread = Thread {
                val origCl = Thread.currentThread().contextClassLoader
                try {
                    Thread.currentThread().contextClassLoader = pluginClass.classLoader
                    val contextClass = android.content.Context::class.java
                    val loadWithContext = try {
                        pluginClass.getMethod("load", contextClass)
                    } catch (e: NoSuchMethodException) { null }

                    if (loadWithContext != null) {
                        System.err.println("  [CS] Calling load(Context) on $className")
                        loadWithContext.invoke(instance, context)
                    } else {
                        System.err.println("  [CS] Calling load() on $className (no Context overload)")
                        instance.load(null)
                    }
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    System.err.println("  [CS] load() failed for $className: ${cause.javaClass.simpleName}: ${cause.message}")
                } finally {
                    Thread.currentThread().contextClassLoader = origCl
                }
            }
            loadThread.isDaemon = true
            loadThread.start()
            loadThread.join(10000L)
            
            // CineStream keeps provider enable/order state in its plugin classloader.
            if (className.contains("CineStream", ignoreCase = true)) {
                try {
                    cineStreamClassLoader = pluginClass.classLoader
                    val settingsClass = pluginClass.classLoader.loadClass("com.megix.settings.Settings")
                    val settings = settingsClass.getField("INSTANCE").get(null)
                    settingsClass.getMethod("initSeenProviders").invoke(settings)
                    val active = settingsClass.getMethod("getActiveProviderOrder").invoke(settings) as? List<*>
                    cineStreamDiagnostic = "provider-order: " + (active?.size ?: -1) + " active [" + (active?.joinToString(",") ?: "") + "]"
                    System.err.println("  [CS-CineStream] " + cineStreamDiagnostic)
                } catch (diag: Throwable) {
                    val cause = diag.cause ?: diag
                    cineStreamDiagnostic = "provider-order diagnostic failed: ${cause.javaClass.simpleName}: ${cause.message}"
                    System.err.println("  [CS-CineStream] " + cineStreamDiagnostic)
                }
                try {
                    val provider = findCineStreamProvider(pluginClass.classLoader, "p_fibwatch")
                    fibwatchAvailable = provider != null
                    System.err.println("  [CS-CineStream] p_fibwatch registry entry present=$fibwatchAvailable")
                } catch (diag: Throwable) {
                    fibwatchAvailable = false
                    val cause = diag.cause ?: diag
                    System.err.println("  [CS-CineStream] p_fibwatch registry check failed: ${cause.javaClass.simpleName}: ${cause.message}")
                }
                try {
                    val provider = findCineStreamProvider(pluginClass.classLoader, "p_skymovies")
                    skymoviesAvailable = provider != null
                    System.err.println("  [CS-CineStream] p_skymovies registry entry present=$skymoviesAvailable")
                } catch (diag: Throwable) {
                    skymoviesAvailable = false
                    val cause = diag.cause ?: diag
                    System.err.println("  [CS-CineStream] p_skymovies registry check failed: ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
            val postApis = com.lagradost.cloudstream3.APIHolder.apis.toList()
            val newApis = postApis.filter { it !in preApis }
            if (newApis.isNotEmpty()) {
                System.err.println("  [CS] Plugin $className registered ${newApis.size} API(s)")
                newApis.forEach { addApiToJson(it, version, it.javaClass.name, jsonArray) }
                return instance
            } else {
                System.err.println("  [CS] Plugin $className registered 0 APIs after load()")
            }
        }
        
        if (isMainApiClass(pluginClass)) {
            val api = instance as? MainAPI
            if (api != null) {
                addApiToJson(api, version, className, jsonArray)
                return instance
            }
        }
        return null
    }

    private fun addApiToJson(apiInstance: MainAPI, version: String, className: String, jsonArray: com.google.gson.JsonArray) {
        val idStr = "cs_" + apiInstance.name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        
        synchronized(jsonArray) {
            if (jsonArray.any { it.asJsonObject.get("id").asString == idStr }) return

            System.err.println("  [CS] Found API: ${apiInstance.name}")
            
            val extObj = JsonObject().apply {
                addProperty("id", idStr)
                addProperty("name", apiInstance.name)
                addProperty("lang", apiInstance.lang)
                addProperty("type", "anime")
                addProperty("baseUrl", apiInstance.mainUrl)
                addProperty("isNsfw", false)
                addProperty("version", version)
                addProperty("pkgName", "cloudstream.plugin")
                addProperty("className", className)
                addProperty("itemType", 1)
                addProperty("hasUpdate", false)
                addProperty("isObsolete", false)
                addProperty("isShared", false)
            }
            jsonArray.add(extObj)
            loadedMap[idStr] = apiInstance
        }
    }

    private fun findCineStreamProvider(loader: ClassLoader, providerKey: String): Any? {
        val registryClass = loader.loadClass("com.megix.ProviderRegistry")
        val registry = registryClass.getField("INSTANCE").get(null)
        val providers = registryClass.getMethod("getBuiltInProviders").invoke(registry) as? Iterable<*>
            ?: return null
        return providers.firstOrNull { provider ->
            provider != null && provider.javaClass.getMethod("getKey").invoke(provider) == providerKey
        }
    }

    /**
     * Execute a single CineStream ProviderRegistry entry. This intentionally does
     * not call CineStream MainAPI.loadLinks: only callbacks emitted by p_fibwatch
     * can cross this RPC boundary.
     */
    suspend fun fetchCineStreamProviderLinks(
        sourceId: String,
        providerKey: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String?,
        tmdbId: Int?,
        onLinkFound: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(providerKey == "p_fibwatch" || providerKey == "p_skymovies") { "Only p_fibwatch and p_skymovies are exposed by this RPC" }
        require(sourceId == "debflix-fibwatch" || sourceId == "debflix-skymovies") { "Invalid CineStream internal provider sourceId" }
        require(title.isNotBlank()) { "title is required" }

        val loader = cineStreamClassLoader ?: error("CineStream plugin classloader is not loaded")
        val provider = findCineStreamProvider(loader, providerKey)
            ?: error("CineStream ProviderRegistry does not contain $providerKey")
        val action = provider.javaClass.getMethod("getExecuteStandard").invoke(provider)
            ?: error("$providerKey has no executeStandard action")
        val extractorsClass = loader.loadClass("com.megix.CineStreamExtractors")
        val extractors = extractorsClass.getField("INSTANCE").get(null)
        val dataClass = loader.loadClass("com.megix.AllLoadLinksData")
        val constructor = dataClass.declaredConstructors.firstOrNull { it.parameterCount == 19 }
            ?: error("Unsupported AllLoadLinksData constructor")
        constructor.isAccessible = true
        val data = constructor.newInstance(
            title, imdbId, tmdbId, null, null, null,
            year, year, season, episode,
            false, false, false, false,
            title, null, null, null, null,
        )
        val subtitleCallback: (Any?) -> Unit = { }
        val linkCallback: (Any?) -> Unit = { link ->
            if (link != null) onLinkFound(gson.toJson(link))
        }
        val invoke = action.javaClass.methods.firstOrNull { it.name == "invoke" && it.parameterCount == 5 }
            ?: error("Unsupported executeStandard function shape")
        invoke.isAccessible = true

        withTimeout(60000L) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                try {
                    invoke.invoke(action, extractors, data, subtitleCallback, linkCallback, continuation)
                } catch (error: InvocationTargetException) {
                    throw (error.targetException ?: error)
                }
            }
        }
    }

    private fun isMainApiClass(clazz: Class<*>): Boolean {
        if (clazz.isInterface || java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return false
        if (MainAPI::class.java.isAssignableFrom(clazz)) return true
        
        var curr: Class<*>? = clazz
        while (curr != null) {
            val name = curr.name
            if (name.contains("MainAPI") || name.contains("Provider")) return true
            if (curr.interfaces.any { it.name.contains("MainAPI") || it.name.contains("Provider") }) return true
            curr = curr.superclass
        }
        return false
    }

    private fun findMainApisInJar(jar: File, classLoader: URLClassLoader, version: String, jsonArray: com.google.gson.JsonArray) {
        val zipFile = ZipFile(jar)
        val candidates = zipFile.entries()
            .toList()
            .filter { it.name.endsWith(".class") && !it.name.contains("$") }
        System.err.println("  [CS] Inspecting ${candidates.size} top-level classes in ${jar.name}")
        for (entry in candidates) {
            val className = entry.name.replace('/', '.').replace('\\', '.').removeSuffix(".class")
            try {
                val clazz = Class.forName(className, false, classLoader)
                if (isMainApiClass(clazz)) {
                    System.err.println("  [CS] Candidate API class: $className")
                    val apiInstance = instantiateApi(clazz) as? MainAPI
                    if (apiInstance != null) {
                        addApiToJson(apiInstance, version, className, jsonArray)
                    } else {
                        System.err.println("    [CS] Could not instantiate $className (not a MainAPI or instantiation failed)")
                    }
                }
            } catch (e: Throwable) {
                System.err.println("    [CS] Skipped $className: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        zipFile.close()
    }

    private fun instantiateApi(clazz: Class<*>): Any? {
        return try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            try {
                clazz.getDeclaredField("INSTANCE").get(null)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    suspend fun search(sourceId: String, query: String, page: Int): String = withContext(Dispatchers.IO) {
        val api = loadedMap[sourceId] ?: return@withContext "{\"list\": [], \"hasNextPage\": false}"
        val origCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = api.javaClass.classLoader
            withTimeout(60000L) {
                val methods = CloudStreamSourceMethods(api)
                val result = methods.search(query, page)
                gson.toJson(result)
            }
        } catch (e: Throwable) {
            System.err.println("[CS-Loader] ERROR: Outer search wrapper failed for $sourceId: ${e.message}")
            e.printStackTrace()
            "{\"list\": [], \"hasNextPage\": false}"
        } finally {
            Thread.currentThread().contextClassLoader = origCl
        }
    }

    suspend fun fetchDetails(sourceId: String, url: String): String = withContext(Dispatchers.IO) {
        val api = loadedMap[sourceId] ?: return@withContext "{}"
        val origCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = api.javaClass.classLoader
            withTimeout(60000L) {
                val methods = CloudStreamSourceMethods(api)
                val result = methods.getDetails(url)
                gson.toJson(result)
            }
        } catch (e: Throwable) {
            "{}"
        } finally {
            Thread.currentThread().contextClassLoader = origCl
        }
    }

    suspend fun fetchVideoList(sourceId: String, url: String): String = withContext(Dispatchers.IO) {
        val api = loadedMap[sourceId] ?: return@withContext "[]"
        val origCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = api.javaClass.classLoader
            withTimeout(60000L) {
                val methods = CloudStreamSourceMethods(api)
                val links = methods.loadLinks(url)
                gson.toJson(links)
            }
        } catch (e: Throwable) {
            "[]"
        } finally {
            Thread.currentThread().contextClassLoader = origCl
        }
    }

    suspend fun fetchVideoListStream(sourceId: String, url: String, onLinkFound: (String) -> Unit) = withContext(Dispatchers.IO) {
        val api = loadedMap[sourceId] ?: return@withContext
        val origCl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = api.javaClass.classLoader
            withTimeout(120000L) {
                val methods = CloudStreamSourceMethods(api)
                methods.loadLinksStream(url) { link ->
                    onLinkFound(gson.toJson(link))
                }
            }
        } catch (e: Throwable) {
        } finally {
            Thread.currentThread().contextClassLoader = origCl
        }
    }
}
