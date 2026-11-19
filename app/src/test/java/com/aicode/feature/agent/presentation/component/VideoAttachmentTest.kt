package com.aicode.feature.agent.presentation.component

import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.VideoPlayerRequest
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 内置视频播放器的纯逻辑：格式判定与来源解析。
 *
 * 播放本身（Dialog + ExoPlayer + PlayerView）需要真实 Compose 组合与解码器，
 * 不放进单测；这里覆盖的是「点开前」的全部判断 —— 哪些文件算视频、点开时按什么顺序找文件。
 */
class VideoAttachmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun attachment(
        fileName: String,
        mimeType: String = "",
        localPath: String = "",
        containerPath: String = ""
    ) = AgentAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = 0L,
        isImage = false
    )

    // ── 格式判定 ──────────────────────────────────────────────

    @Test
    fun isVideoFileName_recognizesDocumentedFormats() {
        // 与 docs-site「图片、视频与安装包」一节列出的格式保持一致
        for (name in listOf(
            "a.mp4", "a.m4v", "a.mkv", "a.webm", "a.mov", "a.avi", "a.3gp", "a.ts", "a.flv", "a.wmv"
        )) {
            assertTrue("应识别为视频: $name", isVideoFileName(name))
        }
    }

    @Test
    fun isVideoFileName_ignoresCase() {
        assertTrue(isVideoFileName("DEMO.MP4"))
        assertTrue(isVideoFileName("clip.MkV"))
    }

    @Test
    fun isVideoFileName_rejectsNonVideo() {
        for (name in listOf("a.txt", "a.png", "a.mp3", "a.apk", "a.zip", "README", "", "noext.")) {
            assertFalse("不应识别为视频: $name", isVideoFileName(name))
        }
    }

    @Test
    fun isVideoFileName_usesLastExtensionOnly() {
        // 备份名不能因为中间出现过 .mp4 就被当成视频
        assertFalse(isVideoFileName("a.mp4.bak"))
        assertTrue(isVideoFileName("a.bak.mp4"))
    }

    @Test
    fun playableExtensions_matchDocumentation() {
        assertEquals(
            setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "wmv"),
            PLAYABLE_VIDEO_EXTENSIONS
        )
    }

    // ── 附件的视频判定 ────────────────────────────────────────

    @Test
    fun attachmentIsVideo_prefersMimeType() {
        // mimeType 说是视频就是视频，哪怕文件名没有扩展名
        assertTrue(attachment("capture", mimeType = "video/mp4").isVideoFile())
        assertTrue(attachment("capture", mimeType = "VIDEO/QUICKTIME").isVideoFile())
    }

    @Test
    fun attachmentIsVideo_fallsBackToFileName() {
        assertTrue(attachment("demo.webm").isVideoFile())
        assertFalse(attachment("demo.mp3", mimeType = "audio/mpeg").isVideoFile())
    }

    @Test
    fun attachmentIsVideo_doesNotTreatImageAsVideo() {
        val image = attachment("photo.png", mimeType = "image/png")
        assertFalse(image.isVideoFile())
    }

    // ── 播放请求构造 ──────────────────────────────────────────

    @Test
    fun toVideoRequest_keepsLocalThenContainerOrder() {
        val request = attachment(
            fileName = "clip.mp4",
            mimeType = "video/mp4",
            localPath = "/data/local/clip.mp4",
            containerPath = "/root/workspace/clip.mp4"
        ).toVideoRequest()

        assertEquals("clip.mp4", request.title)
        assertEquals("video/mp4", request.mimeType)
        assertEquals(
            listOf(
                ImageSource.LocalFile("/data/local/clip.mp4"),
                ImageSource.ContainerPath("/root/workspace/clip.mp4")
            ),
            request.sources
        )
    }

    @Test
    fun toVideoRequest_skipsBlankPaths() {
        val request = attachment(fileName = "clip.mp4", containerPath = "/root/workspace/clip.mp4")
            .toVideoRequest()
        assertEquals(listOf<ImageSource>(ImageSource.ContainerPath("/root/workspace/clip.mp4")), request.sources)

        val empty = attachment(fileName = "clip.mp4").toVideoRequest()
        assertTrue("两个路径都空时不该编出假来源", empty.sources.isEmpty())
    }

    // ── 来源解析 ──────────────────────────────────────────────

    @Test
    fun resolve_localFileExists_returnsPath() = runTest {
        val file = tempFolder.newFile("clip.mp4")
        val resolve = videoFileResolver(mockk<FileAccessProvider>())

        assertEquals(file.absolutePath, resolve(ImageSource.LocalFile(file.absolutePath)))
    }

    @Test
    fun resolve_localFileMissing_returnsNull() = runTest {
        val resolve = videoFileResolver(mockk<FileAccessProvider>())
        val missing = File(tempFolder.root, "gone.mp4")

        assertNull(resolve(ImageSource.LocalFile(missing.absolutePath)))
    }

    @Test
    fun resolve_containerPath_copiesToLocal() = runTest {
        val copied = tempFolder.newFile("pulled.mp4")
        val fileAccess = mockk<FileAccessProvider> {
            every { copyToLocal("/root/workspace/clip.mp4") } returns copied
        }
        val resolve = videoFileResolver(fileAccess)

        assertEquals(copied.absolutePath, resolve(ImageSource.ContainerPath("/root/workspace/clip.mp4")))
    }

    @Test
    fun resolve_containerPathCopyFails_returnsNull() = runTest {
        // 远程模式下拉取失败（断网、文件已删）不能抛给 UI，必须退成 null 让宿主显示「无法播放」
        val fileAccess = mockk<FileAccessProvider> {
            every { copyToLocal(any()) } throws IOException("connection lost")
        }
        val resolve = videoFileResolver(fileAccess)

        assertNull(resolve(ImageSource.ContainerPath("/root/workspace/clip.mp4")))
    }

    @Test
    fun resolve_base64IsUnsupportedForVideo() = runTest {
        // 视频不走 base64 内联：整段塞进内存没有意义，直接放弃这个来源
        val resolve = videoFileResolver(mockk<FileAccessProvider>())

        assertNull(resolve(ImageSource.Base64("AAAA")))
    }

    @Test
    fun resolve_requestFallsBackToSecondSource() = runTest {
        // 复现宿主的取值顺序：第一个来源拿不到时必须继续试下一个
        val copied = tempFolder.newFile("pulled.mp4")
        val fileAccess = mockk<FileAccessProvider> {
            every { copyToLocal("/root/workspace/clip.mp4") } returns copied
        }
        val resolve = videoFileResolver(fileAccess)
        val request: VideoPlayerRequest = attachment(
            fileName = "clip.mp4",
            localPath = File(tempFolder.root, "stale-temp.mp4").absolutePath,
            containerPath = "/root/workspace/clip.mp4"
        ).toVideoRequest()

        val resolved = request.sources.firstNotNullOfOrNull { source ->
            resolve(source)?.takeIf { File(it).isFile }
        }

        assertEquals(copied.absolutePath, resolved)
    }

    @Test
    fun resolve_allSourcesFail_leavesNothingToPlay() = runTest {
        val fileAccess = mockk<FileAccessProvider> {
            every { copyToLocal(any()) } throws IOException("offline")
        }
        val resolve = videoFileResolver(fileAccess)
        val request = attachment(
            fileName = "clip.mp4",
            localPath = File(tempFolder.root, "stale-temp.mp4").absolutePath,
            containerPath = "/root/workspace/clip.mp4"
        ).toVideoRequest()

        val resolved = request.sources.firstNotNullOfOrNull { source ->
            resolve(source)?.takeIf { File(it).isFile }
        }

        assertNull("所有来源都失效时应落到失败态", resolved)
    }

    // ── 临时文件工具 ──────────────────────────────────────────

    @Test
    fun tempFilesUsedByTestsAreRealFiles() {
        // 上面的用例依赖「真实存在的文件才算解析成功」，这里把前提钉住
        val file = Files.createTempFile("video-test", ".mp4").toFile()
        try {
            assertTrue(file.isFile)
            assertTrue(isVideoFileName(file.name))
        } finally {
            file.delete()
        }
    }
}
