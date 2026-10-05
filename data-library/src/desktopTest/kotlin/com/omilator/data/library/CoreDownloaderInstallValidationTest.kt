package com.omilator.data.library

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * isInstalled must validate the dynamic-library format, not just presence:
 * a non-empty garbage file under the core name (historical partial write,
 * saved HTML error page, corruption) must count as missing so Download
 * Cores repairs it instead of pinning it forever.
 */
class CoreDownloaderInstallValidationTest {

    private val dir = Files.createTempDirectory("omilator-cores").toFile()
    private val downloader = CoreDownloader(dir)
    private val entry = downloader.cores.first()

    private fun coreExt(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "dll"
            os.contains("linux") -> "so"
            else -> "dylib"
        }
    }

    private fun libraryMagic(): ByteArray {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)
            os.contains("linux") -> byteArrayOf(
                0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
            )
            // FAT Mach-O wrapper (big-endian bytes).
            else -> byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        }
    }

    private fun coreFile(): File = File(dir, "${entry.name}.${coreExt()}")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun garbageUnderCoreNameIsNotInstalled() {
        coreFile().writeBytes(ByteArray(2048))
        assertFalse(downloader.isInstalled(entry), "non-empty garbage must not count as installed")
    }

    @Test
    fun truncatedHeaderIsNotInstalled() {
        coreFile().writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        assertFalse(downloader.isInstalled(entry), "shorter than a magic number is not a library")
    }

    @Test
    fun magicCarryingFileIsInstalled() {
        coreFile().writeBytes(libraryMagic() + ByteArray(2048))
        assertTrue(downloader.isInstalled(entry), "valid library magic counts as installed")
    }
}
