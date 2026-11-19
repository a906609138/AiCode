package com.aicode.core.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aicode.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import java.io.File

/**
 * 一次视频播放请求。
 *
 * [sources] 与图片查看器同构，按顺序尝试：远程 SSH 模式下已发送附件记的 `localPath` 是临时副本，
 * 进程重启或缓存被清掉后就没了，必须能退回容器路径重新拉一次。
 */
@Immutable
data class VideoPlayerRequest(
    val sources: List<ImageSource>,
    /** 顶部提示与失败文案用，通常是文件名。留空则回退到「视频」。 */
    val title: String = "",
    /** 预期 MIME 类型，仅用于失败提示，不参与解码决策。 */
    val mimeType: String = ""
)

/** 打开内置视频播放器的句柄：调用方只管 show，播放器生命周期由宿主负责。 */
@Stable
fun interface VideoPlayer {
    fun show(request: VideoPlayerRequest)
}

/**
 * 默认是空实现：没挂 [VideoPlayerHost] 的组合树里点视频静默无反应，不会崩。
 * 与 [LocalImageViewer] 同一套路，避免中间层为了转发 lambda 改签名。
 */
val LocalVideoPlayer = staticCompositionLocalOf<VideoPlayer> { VideoPlayer { } }

@Stable
class VideoPlayerState : VideoPlayer {
    var request by mutableStateOf<VideoPlayerRequest?>(null)
        private set

    override fun show(request: VideoPlayerRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberVideoPlayerState(): VideoPlayerState = remember { VideoPlayerState() }

private sealed interface VideoLoad {
    data object Loading : VideoLoad
    data object Failed : VideoLoad
    data class Ready(val path: String) : VideoLoad
}

/**
 * 内置视频播放器宿主。
 *
 * 用 Dialog 而不是组合内 overlay：与 [ImageViewerHost] 同样的三条理由（避开全局背景水印、
 * 平板双栏也能盖满整屏、返回键关闭且窗口优先级高于侧栏 BackHandler）。
 *
 * @param resolve 把 [ImageSource] 落成宿主本地文件路径；实现方自行切 IO 线程，返回 null 表示这个来源拿不到。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerHost(
    state: VideoPlayerState,
    resolve: suspend (ImageSource) -> String?
) {
    val request = state.request ?: return
    val context = LocalContext.current

    val load by produceState<VideoLoad>(VideoLoad.Loading, request, resolve) {
        val resolved = request.sources.firstNotNullOfOrNull { source ->
            runCatching { resolve(source) }.getOrNull()?.takeIf { File(it).isFile }
        }
        value = if (resolved != null) VideoLoad.Ready(resolved) else VideoLoad.Failed
    }

    // 播放器与对话框同生命周期：一次打开建一个，关掉就 release，不留后台音频。
    // 变量名带 exo 前缀，避免在与 PlayerView 的属性 player 同处的作用域里被遮蔽。
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 浅色主题下系统栏图标是深色，压在黑底上看不见。dialog 有自己的 window，单独调它的图标色。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.pause()
                exoPlayer.release()
            }
        }

        val current = load
        val readyPath = (current as? VideoLoad.Ready)?.path
        LaunchedEffect(readyPath) {
            if (readyPath != null) {
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(readyPath))))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when (current) {
                is VideoLoad.Ready -> AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                            player = exoPlayer
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    onRelease = { view ->
                        // 先摘掉 player 再让它被回收：PlayerView 持有引用，不摘会拖住已 release 的实例。
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
                VideoLoad.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.chat_video_preparing),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                VideoLoad.Failed -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.chat_video_unplayable),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }

            IconButton(
                onClick = state::dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White
                )
            }
        }
    }
}
