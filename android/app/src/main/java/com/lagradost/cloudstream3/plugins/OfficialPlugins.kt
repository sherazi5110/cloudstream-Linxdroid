package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.github.sherazi5110.cloudstream.PluginManager as AppPlugins
import io.github.sherazi5110.cloudstream.RepositoryManager as AppRepos

// The official app's plugin classes, with its names and signatures, for extensions that manage other
// extensions (Ultima, StreamPlay, StremioX). They work on this app's own extension manager.

@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginData(
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("url") val url: String?,
    @JsonProperty("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") val filePath: String,
    @JsonProperty("version") val version: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Repository(
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String?,
    @JsonProperty("manifestVersion") val manifestVersion: Int,
    @JsonProperty("pluginLists") val pluginLists: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SitePlugin(
    @JsonProperty("url") val url: String,
    @JsonProperty("status") val status: Int,
    @JsonProperty("version") val version: Int,
    @JsonProperty("apiVersion") val apiVersion: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("authors") val authors: List<String>,
    @JsonProperty("description") val description: String?,
    @JsonProperty("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("tvTypes") val tvTypes: List<String>?,
    @JsonProperty("language") val language: String?,
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("fileSize") val fileSize: Long?,
    @JsonProperty("fileHash") val fileHash: String?,
)

data class PluginWrapper(
    @JsonProperty("repository") val repository: Repository,
    @JsonProperty("repositoryData") val repositoryData: RepositoryData,
    @JsonProperty("plugin") val plugin: SitePlugin,
)

object PluginManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    /** Loaded extensions by file path, like the official app. */
    val plugins: MutableMap<String, BasePlugin>
        get() = AppPlugins.loaded.mapValues { it.value.instance }.toMutableMap()

    fun getPluginsOnline(): Array<PluginData> =
        AppPlugins.installed().filter { it.isOnline }.map { PluginData(it.internalName, it.url, true, it.filePath, it.version) }.toTypedArray()

    fun getPluginsLocal(): Array<PluginData> =
        AppPlugins.installed().filter { !it.isOnline }.map { PluginData(it.internalName, it.url, false, it.filePath, it.version) }.toTypedArray()

    fun unloadPlugin(absolutePath: String) {
        AppPlugins.unloadPlugin(absolutePath)
        AppPlugins.bumpVersion()
    }

    private fun sanitize(name: String) = AppRepos.sanitizedFileName(name)

    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File =
        File("${context.filesDir}/$ONLINE_PLUGINS_FOLDER/${sanitize(repositoryUrl)}/${sanitize(internalName)}.cs3")

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean = withContext(Dispatchers.IO) {
        val data = AppPlugins.installed().firstOrNull { it.internalName.replace("provider", "", ignoreCase = true) == apiName }
            ?: return@withContext false
        AppPlugins.loadPlugin(File(data.filePath), data).isSuccess.also { if (it) AppPlugins.bumpVersion() }
    }
}

object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    fun getRepositories(): Array<RepositoryData> =
        AppRepos.getRepositories().map { RepositoryData(it.iconUrl, it.name, it.url) }.toTypedArray()

    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
        val mine = AppRepos.getRepositories().firstOrNull { it.url == repositoryData.url } ?: return null
        return AppRepos.getRepoPlugins(mine)?.map { w ->
            val p = w.plugin
            PluginWrapper(
                Repository(w.repository.iconUrl, w.repository.name, w.repository.description, w.repository.manifestVersion, w.repository.pluginLists),
                repositoryData,
                SitePlugin(p.url, p.status, p.version, p.apiVersion, p.name, p.internalName, p.authors, p.description, p.repositoryUrl,
                    p.tvTypes, p.language, p.iconUrl, p.fileSize, p.fileHash),
            )
        }
    }
}
