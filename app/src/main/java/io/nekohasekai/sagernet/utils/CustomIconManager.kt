package io.nekohasekai.sagernet.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object CustomIconManager {

    const val FILE_ICON = "icon.png"
    const val FILE_TILE = "tile.png"
    const val REQUIRED_WIDTH = 512
    const val REQUIRED_HEIGHT = 512

    sealed class ImportResult {
        object Success : ImportResult()
        data class MissingFile(val fileName: String) : ImportResult()
        data class InvalidDimension(val fileName: String, val width: Int, val height: Int) : ImportResult()
        data class NotPng(val fileName: String) : ImportResult()
        data class SecurityError(val reason: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    private fun getIconDir(context: Context = app): File {
        return context.filesDir.resolve("custom_icon").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getIconFile(context: Context = app): File = getIconDir(context).resolve(FILE_ICON)
    fun getTileFile(context: Context = app): File = getIconDir(context).resolve(FILE_TILE)
    private fun getAppliedFile(context: Context = app): File = getIconDir(context).resolve("tile_applied")

    fun hasCustomIcon(context: Context = app): Boolean = getIconFile(context).exists()
    fun hasCustomTile(context: Context = app): Boolean = getTileFile(context).exists()
    fun isCustomActive(context: Context = app): Boolean = hasCustomIcon(context) && hasCustomTile(context)

    fun isTileApplied(context: Context = app): Boolean = getAppliedFile(context).exists() && hasCustomTile(context)

    fun setTileApplied(context: Context = app, applied: Boolean) {
        val marker = getAppliedFile(context)
        if (applied) {
            if (!marker.exists()) {
                try {
                    marker.createNewFile()
                } catch (e: Throwable) {
                    // ignore
                }
            }
        } else {
            if (marker.exists()) {
                marker.delete()
            }
        }
    }

    /**
     * Restores the default icons.
     */
    fun reset(context: Context = app): Boolean {
        var success = true
        setTileApplied(context, false)
        val iconFile = getIconFile(context)
        if (iconFile.exists() && !iconFile.delete()) success = false
        val tileFile = getTileFile(context)
        if (tileFile.exists() && !tileFile.delete()) success = false
        return success
    }

    /**
     * Validates and installs a ZIP icon pack.
     */
    fun importIconPack(inputStream: InputStream, context: Context = app): ImportResult {
        val tempDir = File(context.cacheDir, "temp_icon_pack_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) {
            return ImportResult.Error(context.getString(R.string.icon_pack_temp_dir_failed))
        }

        try {
            val extractedFiles = mutableSetOf<String>()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    // Reject path traversal (zip slip)
                    if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                        return ImportResult.SecurityError(context.getString(R.string.icon_pack_unsafe_path, entryName))
                    }

                    // Only icon.png and tile.png, at the root or one directory down
                    val fileName = File(entryName).name.lowercase()
                    if (fileName == FILE_ICON || fileName == FILE_TILE) {
                        val targetFile = File(tempDir, fileName)
                        targetFile.outputStream().use { os ->
                            zis.copyTo(os)
                        }
                        extractedFiles.add(fileName)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!extractedFiles.contains(FILE_ICON)) {
                return ImportResult.MissingFile(FILE_ICON)
            }
            if (!extractedFiles.contains(FILE_TILE)) {
                return ImportResult.MissingFile(FILE_TILE)
            }

            val tempIcon = File(tempDir, FILE_ICON)
            val tempTile = File(tempDir, FILE_TILE)

            // Size and format checks
            val iconDim = getPngDimensions(tempIcon) ?: return ImportResult.NotPng(FILE_ICON)
            if (iconDim.first != REQUIRED_WIDTH || iconDim.second != REQUIRED_HEIGHT) {
                return ImportResult.InvalidDimension(FILE_ICON, iconDim.first, iconDim.second)
            }

            val tileDim = getPngDimensions(tempTile) ?: return ImportResult.NotPng(FILE_TILE)
            if (tileDim.first != REQUIRED_WIDTH || tileDim.second != REQUIRED_HEIGHT) {
                return ImportResult.InvalidDimension(FILE_TILE, tileDim.first, tileDim.second)
            }

            // All checks passed: replace the app's private copies atomically
            val targetIcon = getIconFile(context)
            val targetTile = getTileFile(context)

            tempIcon.copyTo(targetIcon, overwrite = true)
            tempTile.copyTo(targetTile, overwrite = true)

            // An import is only previewed; the tile changes once the user applies the pack
            setTileApplied(context, false)

            return ImportResult.Success
        } catch (e: Exception) {
            return ImportResult.Error(e.message ?: context.getString(R.string.icon_pack_extract_failed))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** The imported pack as backup entries ([FILE_ICON], [FILE_TILE]); null without a complete pack. */
    fun exportIconPack(context: Context = app): Map<String, ByteArray>? {
        if (!isCustomActive(context)) return null
        return try {
            mapOf(FILE_ICON to getIconFile(context).readBytes(), FILE_TILE to getTileFile(context).readBytes())
        } catch (e: Exception) {
            null
        }
    }

    /** Installs a pack from backup entries after the checks of [importIconPack]; the tile is not applied. */
    fun restoreIconPack(entries: Map<String, ByteArray>, context: Context = app): ImportResult {
        for (name in listOf(FILE_ICON, FILE_TILE)) {
            val bytes = entries[name] ?: return ImportResult.MissingFile(name)
            val dim = parsePngHeader(ByteArrayInputStream(bytes)) ?: return ImportResult.NotPng(name)
            if (dim.first != REQUIRED_WIDTH || dim.second != REQUIRED_HEIGHT) {
                return ImportResult.InvalidDimension(name, dim.first, dim.second)
            }
        }
        return try {
            for ((name, target) in listOf(FILE_ICON to getIconFile(context), FILE_TILE to getTileFile(context))) {
                val tmp = File(target.parentFile, "$name.tmp")
                tmp.writeBytes(entries.getValue(name))
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return ImportResult.Error("cannot write $name")
                }
            }
            setTileApplied(context, false)
            ImportResult.Success
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Reads the size from the PNG header, so an oversized image is never decoded.
     * Also works without the Android runtime (unit tests).
     */
    fun getPngDimensions(file: File): Pair<Int, Int>? {
        if (!file.exists() || file.length() < 24) return null
        return try {
            file.inputStream().use { parsePngHeader(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun parsePngHeader(inputStream: InputStream): Pair<Int, Int>? {
        val header = ByteArray(24)
        var readTotal = 0
        while (readTotal < 24) {
            val r = inputStream.read(header, readTotal, 24 - readTotal)
            if (r == -1) break
            readTotal += r
        }
        if (readTotal < 24) return null

        // PNG signature: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
        val isPng = header[0] == 0x89.toByte() &&
                header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() &&
                header[3] == 0x47.toByte() &&
                header[4] == 0x0D.toByte() &&
                header[5] == 0x0A.toByte() &&
                header[6] == 0x1A.toByte() &&
                header[7] == 0x0A.toByte()
        if (!isPng) return null

        // IHDR chunk: bytes 12-15 are "IHDR" (0x49 0x48 0x44 0x52)
        val isIhdr = header[12] == 0x49.toByte() &&
                header[13] == 0x48.toByte() &&
                header[14] == 0x44.toByte() &&
                header[15] == 0x52.toByte()
        if (!isIhdr) return null

        // Width at 16-19, height at 20-23 (32-bit big endian)
        val dis = DataInputStream(ByteArrayInputStream(header, 16, 8))
        val width = dis.readInt()
        val height = dis.readInt()
        return Pair(width, height)
    }

    /**
     * The custom app icon as a full-colour bitmap.
     */
    fun loadIconBitmap(context: Context = app): Bitmap? {
        val file = getIconFile(context)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * The tile icon as a single-colour alpha mask (white RGB, alpha kept).
     */
    fun loadTileAlphaBitmap(context: Context = app): Bitmap? {
        val file = getTileFile(context)
        if (!file.exists()) return null
        val rawBitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Throwable) {
            null
        } ?: return null

        return extractAlphaMask(rawBitmap)
    }

    /**
     * Keeps only the alpha channel: a white (0xFFFFFFFF) ARGB_8888 mask with the original alpha.
     */
    fun extractAlphaMask(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val alphaBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i])
            pixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        alphaBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return alphaBitmap
    }
}
