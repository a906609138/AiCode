package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.ImageViewerRequest
import com.aicode.core.ui.VideoPlayerRequest
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatImageLoader"

/**
 * 聊天区图片加载器：把 [ImageSource] 落成位图交给全屏查看器。全项目只有这一份解码策略。
 *
 * [ImageSource.ContainerPath] 经 [FileAccessProvider.copyToLocal] 落到宿主文件 —— 本地 PRoot 模式
 * 只是路径映射、不产生拷贝；远程 SSH 模式会把文件 base64 拉回来写进临时文件。
 */
internal fun chatImageLoader(
    fileAccess: FileAccessProvider,
    maxEdge: Int,
    maxPixels: Long
): suspend (ImageSource) -> ImageBitmap? = { source ->
    withContext(Dispatchers.IO) {
        try {
            when (source) {
                is ImageSource.LocalFile ->
                    decodeExistingFile(File(source.path), maxEdge, maxPixels)

                is ImageSource.ContainerPath ->
                    decodeExistingFile(fileAccess.copyToLocal(source.path), maxEdge, maxPixels)

                is ImageSource.Base64 ->
                    decodeSampledBitmap(Base64.getDecoder().decode(source.data), maxEdge, maxPixels)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "图片加载失败: $source", e)
            null
        }
    }
}

private fun decodeExistingFile(file: File, maxEdge: Int, maxPixels: Long): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeSampledBitmap(file.absolutePath, maxEdge, maxPixels)
}

/**
 * 待发送图片附件的查看请求。
 *
 * `localPath` 优先、base64 兜底：`decodeByteArray` 要完整 byte[] 常驻，加上本来就压在内存里的
 * base64 字符串（UTF-16 实占两倍），一张 5MB 的图瞬时要多吃十几 MB；从磁盘解码没这份开销。
 */
internal fun PendingUploadAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        image?.base64Data?.takeIf { it.isNotBlank() }?.let { ImageSource.Base64(it) }
    ),
    title = fileName
)

/**
 * 已发送附件的查看请求。
 *
 * 带上 `containerPath` 兜底：远程模式下 `sendFile` 记的 `localPath` 来自 `createTempFile` +
 * `deleteOnExit`，进程重启或缓存被清掉后文件就没了，这时得能退回容器路径重新拉一次。
 */
internal fun AgentAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }
    ),
    title = fileName
)

/**
 * Markdown 内嵌图片的查看请求：link 就是 AI 视角的容器路径。
 *
 * 库把 alt 直接交给 Image 当 contentDescription，不经过 transformer，所以这里拿不到它，
 * 退而取路径末段的文件名当标题。
 */
/**
 * 视频来源解析器：把 [ImageSource] 落成宿主本地文件路径交给 ExoPlayer。
 *
 * 与 [chatImageLoader] 的分工一致 —— 容器 / 远程路径经 [FileAccessProvider.copyToLocal] 落地，
 * 本地 PRoot 模式只是路径映射；[ImageSource.Base64] 对视频不适用（不把整段视频塞进内存），直接返回 null。
 */
internal fun videoFileResolver(
    fileAccess: FileAccessProvider
): suspend (ImageSource) -> String? = { source ->
    withContext(Dispatchers.IO) {
        when (source) {
            is ImageSource.LocalFile -> source.path.takeIf { File(it).isFile }
            is ImageSource.ContainerPath -> runCatching { fileAccess.copyToLocal(source.path) }
                .getOrNull()
                ?.takeIf { it.isFile }
                ?.absolutePath
            is ImageSource.Base64 -> null
        }
    }
}

/**
 * 视频附件的播放请求。
 *
 * 与图片一样带 `containerPath` 兜底：远程模式下 `localPath` 是临时副本，失效后要能退回容器路径重取。
 */
internal fun AgentAttachment.toVideoRequest(): VideoPlayerRequest = VideoPlayerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }
    ),
    title = fileName,
    mimeType = mimeType
)

/**
 * 视频附件的常见容器格式；[AgentAttachment.mimeType] 缺失时按扩展名兜底。
 *
 * 同时被 [FileTypeIcon] 用来决定文件图标，保持「显示播放三角」与「点开能播放」是同一套判定。
 */
internal val PLAYABLE_VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "wmv"
)

/** 是否是视频：内置播放器接管这类附件，其余仍交给系统 app。 */
internal fun AgentAttachment.isVideoFile(): Boolean {
    if (mimeType.startsWith("video/", ignoreCase = true)) return true
    return isVideoFileName(fileName)
}

/**
 * 按文件名判断是不是可播放的视频。
 *
 * 抽成独立函数是因为调用方不止附件：文件树里点视频也该进内置播放器，那里只有路径、没有 mimeType。
 */
internal fun isVideoFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in PLAYABLE_VIDEO_EXTENSIONS
}

internal fun markdownViewerRequest(link: String): ImageViewerRequest = ImageViewerRequest(
    sources = listOf(ImageSource.ContainerPath(link)),
    title = link.trimEnd('/').substringAfterLast('/')
)
