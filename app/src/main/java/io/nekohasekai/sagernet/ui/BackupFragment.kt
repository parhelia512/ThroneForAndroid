package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private lateinit var binding: LayoutBackupBinding
    private lateinit var backupData: ByteArray
    private var isWebDAVBackup = false
    private var isBackupInProgress = false
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        // 如果正在进行恢复操作，取消它
        if (isRestoreInProgress) {
            restoreJob?.cancel()
            restoreJob = null
            isRestoreInProgress = false
            MessageStore.showMessage(requireActivity(), R.string.restore_cancelled)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    // 宿主缺失时逐级回退（Fragment context → 前台 Activity → 应用级 Context）
                    val resolverContext = context ?: MessageStore.getCurrentActivity() ?: app
                    resolverContext.contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, "throne_backup_${Date().toLocaleString()}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "throne_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeBytes(backupData)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        binding.actionImportThroneDesktop.setOnClickListener {
            startFilesForResult(importThroneDesktopFile, "*/*")
        }

        setupWebDAV(binding)
    }

    private fun setupWebDAV(binding: LayoutBackupBinding) {
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }
        
        binding.backupToWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            backupToWebDAV()
        }
        
        binding.restoreFromWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            restoreFromWebDAV()
        }
    }

    private fun backupToWebDAV() {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        val activity = requireActivity()
        runOnDefaultDispatcher {
            try {
                isWebDAVBackup = true
                val backupData = doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true   // 备份设置
                )
                isWebDAVBackup = false
                
                val client = OkHttpClient()

                // 规范化 URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Throne"

                // 使用英文格式的时间戳作为文件名，修改后缀为 .zip
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "throne_backup_${version}_$timestamp.zip"

                // 确保 baseUrl 是有效的 URL
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                // 使用 HttpUrl 构建路径，避免 # 等特殊字符被当作 fragment
                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // 先检查目录是否存在
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // 目录存在
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // 如果需要，创建目录
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // 上传文件时使用正确的 Content-Type
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody("application/zip".toMediaType()))
                    .apply {
                        header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.webdav_backup_success)
                }
            } catch (e: Exception) {
                isWebDAVBackup = false  // 确保发生异常时也重置标志
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }
                
                onMainDispatcher {
                    MessageStore.showMessage(activity, errorMessage)
                }
            } finally {
                isBackupInProgress = false
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = requireActivity()
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = OkHttpClient()
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Throne"

                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // 先列出目录内容找到最新的备份文件
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "1")
                    .build()

                // 获取最新的备份文件名
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")
                    
                    val patterns = listOf(
                        """<D:href>[^<]*?throne_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</D:href>""".toRegex(),
                        """<d:href>[^<]*?throne_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</d:href>""".toRegex(),
                        """<href>[^<]*?throne_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</href>""".toRegex()
                    )
                    
                    val backupFiles = mutableListOf<String>()
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """throne_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex()
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }
                    
                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.bytes() ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // 根据文件类型处理内容
                val backupContent = if (latestBackup.endsWith(".zip")) {
                    // ZIP 文件处理
                    ZipInputStream(content.inputStream()).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    // JSON 文件处理
                    content.toString(Charsets.UTF_8)
                }

                // 解析并导入备份数据
                val json = JSONObject(backupContent)
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                        return@onMainDispatcher
                    }

                    val import = LayoutImportBinding.inflate(layoutInflater)
                    if (!json.has("profiles")) {
                        import.backupConfigurations.isVisible = false
                    }
                    if (!json.has("rules")) {
                        import.backupRules.isVisible = false
                    }
                    if (!json.has("settings")) {
                        import.backupSettings.isVisible = false
                    }

                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                        .setView(import.root)
                        .setPositiveButton(R.string.backup_import) { _, _ ->
                            SagerNet.stopService()

                            val binding = LayoutProgressBinding.inflate(layoutInflater)
                            binding.content.text = getString(R.string.backup_importing)
                            val dialog = AlertDialog.Builder(requireContext())
                                .setView(binding.root)
                                .setCancelable(false)
                                .show()
                            runOnDefaultDispatcher {
                                runCatching {
                                    // 再次检查是否已被取消
                                    if (!isAdded) {
                                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                                        return@runOnDefaultDispatcher
                                    }
                                    finishImport(
                                        json,
                                        import.backupConfigurations.isChecked,
                                        import.backupRules.isChecked,
                                        import.backupSettings.isChecked
                                    )
                                    ProcessPhoenix.triggerRebirth(
                                        activity, Intent(activity, MainActivity::class.java)
                                    )
                                }.onFailure {
                                    Logs.w(it)
                                    onMainDispatcher {
                                        MessageStore.showMessage(activity, it.readableMessage)
                                    }
                                }

                                onMainDispatcher {
                                    dialog.dismiss()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    companion object {
        const val BACKUP_VERSION = 2
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean
    ): ByteArray {
        val out = JSONObject().apply {
            put("version", BACKUP_VERSION)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(profileToJson(it))
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(groupToJson(it))
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(ruleToJson(it))
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }

        val jsonContent = out.toStringPretty()
        return if (isWebDAVBackup) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)

                    val entry = ZipEntry("throne_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    }

                    // 写入数据
                    zos.putNextEntry(entry)
                    val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                    zos.write(bytes)
                    zos.closeEntry()

                    // 确保所有数据都被写入和压缩
                    zos.finish()
                }
                bos.toByteArray()
            }
        } else {
            // 本地导出和分享功能使用 JSON 格式
            jsonContent.toByteArray()
        }
    }

    // Backup format 2: profiles, groups and rules are plain JSON objects (the profile payload is the desktop's
    // ExportToJson text); settings stay marshalled KeyValuePair parcels. Format 1 (Kryo beans) is not restorable.

    private fun profileToJson(entity: ProxyEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("groupId", entity.groupId)
        put("type", entity.type)
        put("outboundJson", entity.outboundJson)
        put("userOrder", entity.userOrder)
        put("tx", entity.tx)
        put("rx", entity.rx)
        put("status", entity.status)
        put("ping", entity.ping)
        put("uuid", entity.uuid)
        put("error", entity.error)
        put("speedTestMode", entity.speedTestMode)
        put("speedTestDownloadBitsPerSecond", entity.speedTestDownloadBitsPerSecond)
        put("speedTestUploadBitsPerSecond", entity.speedTestUploadBitsPerSecond)
    }

    private fun profileFromJson(obj: JSONObject): ProxyEntity = ProxyEntity(
        id = obj.optLong("id"),
        groupId = obj.optLong("groupId"),
        type = obj.optString("type"),
        outboundJson = obj.optString("outboundJson"),
        userOrder = obj.optLong("userOrder"),
        tx = obj.optLong("tx"),
        rx = obj.optLong("rx"),
        status = obj.optInt("status"),
        ping = obj.optInt("ping"),
        uuid = obj.optString("uuid"),
        error = if (obj.isNull("error")) null else obj.optString("error"),
        speedTestMode = obj.optString("speedTestMode"),
        speedTestDownloadBitsPerSecond = obj.optLong("speedTestDownloadBitsPerSecond"),
        speedTestUploadBitsPerSecond = obj.optLong("speedTestUploadBitsPerSecond"),
    )

    private fun groupToJson(group: ProxyGroup): JSONObject = JSONObject().apply {
        put("id", group.id)
        put("userOrder", group.userOrder)
        put("ungrouped", group.ungrouped)
        put("name", group.name)
        put("type", group.type)
        put("order", group.order)
        put("isSelector", group.isSelector)
        put("frontProxy", group.frontProxy)
        put("landingProxy", group.landingProxy)
        group.subscription?.let { sub ->
            put("subscription", JSONObject().apply {
                put("type", sub.type)
                put("link", sub.link)
                put("token", sub.token)
                put("forceResolve", sub.forceResolve)
                put("deduplication", sub.deduplication)
                put("updateWhenConnectedOnly", sub.updateWhenConnectedOnly)
                put("customUserAgent", sub.customUserAgent)
                put("autoUpdate", sub.autoUpdate)
                put("autoUpdateDelay", sub.autoUpdateDelay)
                put("lastUpdated", sub.lastUpdated)
                put("filterMode", sub.filterMode)
                put("filterRegex", sub.filterRegex)
                put("serverDnsResolver", sub.serverDnsResolver)
                put("bytesUsed", sub.bytesUsed)
                put("bytesRemaining", sub.bytesRemaining)
                put("username", sub.username)
                put("expiryDate", sub.expiryDate)
                put("protocols", JSONArray(sub.protocols ?: emptyList<String>()))
                put("subscriptionUserinfo", sub.subscriptionUserinfo)
            })
        }
    }

    private fun groupFromJson(obj: JSONObject): ProxyGroup = ProxyGroup(
        id = obj.optLong("id"),
        userOrder = obj.optLong("userOrder"),
        ungrouped = obj.optBoolean("ungrouped"),
        name = if (obj.isNull("name")) null else obj.optString("name"),
        type = obj.optInt("type"),
        order = obj.optInt("order"),
        isSelector = obj.optBoolean("isSelector"),
        frontProxy = obj.optLong("frontProxy", -1L),
        landingProxy = obj.optLong("landingProxy", -1L),
    ).apply {
        val subObj = obj.optJSONObject("subscription") ?: return@apply
        subscription = SubscriptionBean().apply {
            initializeDefaultValues()
            if (!subObj.isNull("type")) type = subObj.optInt("type")
            if (!subObj.isNull("link")) link = subObj.optString("link")
            if (!subObj.isNull("token")) token = subObj.optString("token")
            if (!subObj.isNull("forceResolve")) forceResolve = subObj.optBoolean("forceResolve")
            if (!subObj.isNull("deduplication")) deduplication = subObj.optBoolean("deduplication")
            if (!subObj.isNull("updateWhenConnectedOnly")) updateWhenConnectedOnly = subObj.optBoolean("updateWhenConnectedOnly")
            if (!subObj.isNull("customUserAgent")) customUserAgent = subObj.optString("customUserAgent")
            if (!subObj.isNull("autoUpdate")) autoUpdate = subObj.optBoolean("autoUpdate")
            if (!subObj.isNull("autoUpdateDelay")) autoUpdateDelay = subObj.optInt("autoUpdateDelay")
            if (!subObj.isNull("lastUpdated")) lastUpdated = subObj.optInt("lastUpdated")
            if (!subObj.isNull("filterMode")) filterMode = subObj.optInt("filterMode")
            if (!subObj.isNull("filterRegex")) filterRegex = subObj.optString("filterRegex")
            if (!subObj.isNull("serverDnsResolver")) serverDnsResolver = subObj.optString("serverDnsResolver")
            if (!subObj.isNull("bytesUsed")) bytesUsed = subObj.optLong("bytesUsed")
            if (!subObj.isNull("bytesRemaining")) bytesRemaining = subObj.optLong("bytesRemaining")
            if (!subObj.isNull("username")) username = subObj.optString("username")
            if (!subObj.isNull("expiryDate")) expiryDate = subObj.optInt("expiryDate")
            subObj.optJSONArray("protocols")?.let { arr ->
                protocols = (0 until arr.length()).map { arr.optString(it) }
            }
            if (!subObj.isNull("subscriptionUserinfo")) subscriptionUserinfo = subObj.optString("subscriptionUserinfo")
        }
    }

    private fun ruleToJson(rule: RuleEntity): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("config", rule.config)
        put("userOrder", rule.userOrder)
        put("enabled", rule.enabled)
        put("domains", rule.domains)
        put("ip", rule.ip)
        put("port", rule.port)
        put("sourcePort", rule.sourcePort)
        put("network", rule.network)
        put("source", rule.source)
        put("protocol", rule.protocol)
        put("ruleset", rule.ruleset)
        put("outbound", rule.outbound)
        put("packages", JSONArray(rule.packages.toList()))
    }

    private fun ruleFromJson(obj: JSONObject): RuleEntity = RuleEntity(
        id = obj.optLong("id"),
        name = obj.optString("name"),
        config = obj.optString("config"),
        userOrder = obj.optLong("userOrder"),
        enabled = obj.optBoolean("enabled"),
        domains = obj.optString("domains"),
        ip = obj.optString("ip"),
        port = obj.optString("port"),
        sourcePort = obj.optString("sourcePort"),
        network = obj.optString("network"),
        source = obj.optString("source"),
        protocol = obj.optString("protocol"),
        ruleset = obj.optString("ruleset"),
        outbound = obj.optLong("outbound"),
        packages = obj.optJSONArray("packages")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            ?: emptySet(),
    )

    /** The objects of a format-2 array; a format-1 (Kryo) array holds base64 strings and cannot be restored. */
    private fun objectsOf(content: JSONObject, key: String): List<JSONObject> {
        val array = content.getJSONArray(key)
        return (0 until array.length()).map {
            array.optJSONObject(it) ?: throw IllegalStateException(getString(R.string.backup_version_unsupported))
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    private val importThroneDesktopFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                runOnDefaultDispatcher {
                    startImportThroneDesktop(file)
                }
            }
        }

    private suspend fun startImportThroneDesktop(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".thrbackup", ignoreCase = true)) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_throne_desktop, fileName)).show()
            }
            return
        }

        try {
            val bytes = requireContext().contentResolver.openInputStream(file)!!.use { it.readBytes() }
            val parsed = ThroneDesktopBackupImporter.parse(bytes, app.cacheDir)
            onMainDispatcher {
                if (!isAdded) {
                    parsed.dbFile.delete()
                    return@onMainDispatcher
                }
                val import = LayoutImportBinding.inflate(layoutInflater)
                // reuse import checkboxes; hide unavailable parts
                if (!parsed.hasProfiles) import.backupConfigurations.isVisible = false
                if (!parsed.hasRoutes) import.backupRules.isVisible = false
                if (!parsed.hasSettings) import.backupSettings.isVisible = false

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.action_import_throne_desktop)
                    .setMessage(R.string.backup_import_throne_desktop_summary)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        SagerNet.stopService()
                        val progress = LayoutProgressBinding.inflate(layoutInflater)
                        progress.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(progress.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                ThroneDesktopBackupImporter.import(
                                    parsed,
                                    import.backupConfigurations.isChecked && parsed.hasProfiles,
                                    import.backupRules.isChecked && parsed.hasRoutes,
                                    import.backupSettings.isChecked && parsed.hasSettings,
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                parsed.dbFile.delete()
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        parsed.dbFile.delete()
                    }
                    .setOnCancelListener {
                        parsed.dbFile.delete()
                    }
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val content = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }

            val json = JSONObject(content)
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    import.backupConfigurations.isChecked,
                                    import.backupRules.isChecked,
                                    import.backupSettings.isChecked
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        if (profile && content.has("profiles")) {
            val profiles = objectsOf(content, "profiles").map { profileFromJson(it) }
            val groups = if (content.has("groups")) objectsOf(content, "groups").map { groupFromJson(it) } else emptyList()
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)
            if (groups.isNotEmpty()) {
                SagerDatabase.groupDao.reset()
                SagerDatabase.groupDao.insert(groups)
            }
        }
        if (rule && content.has("rules")) {
            val rules = objectsOf(content, "rules").map { ruleFromJson(it) }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
        }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }

    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
