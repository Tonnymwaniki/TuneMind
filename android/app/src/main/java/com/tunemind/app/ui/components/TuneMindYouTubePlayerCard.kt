package com.tunemind.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tunemind.app.provider.TuneMindYouTubeEmbedController

private const val YOUTUBE_PLAYER_ORIGIN = "https://com.tunemind.app"

private fun buildYouTubeEmbedHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<meta name="referrer" content="strict-origin-when-cross-origin" />
<style>
  html, body {
    margin: 0;
    padding: 0;
    width: 100%;
    height: 100%;
    background: #050507;
    overflow: hidden;
  }
  #player {
    width: 100%;
    height: 100%;
  }
</style>
</head>
<body>
<div id="player"></div>
<script>
  var tmPlayer = null;
  var tmTicker = null;

  function onYouTubeIframeAPIReady() {
    tmPlayer = new YT.Player('player', {
      width: '100%',
      height: '100%',
      playerVars: {
        autoplay: 1,
        controls: 1,
        playsinline: 1,
        rel: 0,
        modestbranding: 1,
        enablejsapi: 1,
        origin: '$YOUTUBE_PLAYER_ORIGIN'
      },
      events: {
        onReady: function() {
          try { TuneMindYT.onPlayerReady(); } catch (e) {}
        },
        onStateChange: function(ev) {
          try { TuneMindYT.onStateChange(ev.data); } catch (e) {}
          if (ev.data === 1) {
            startTicker();
          } else if (ev.data === 0 || ev.data === 2) {
            stopTicker();
            reportProgress();
          }
        },
        onError: function(ev) {
          stopTicker();
          try { TuneMindYT.onError(ev.data); } catch (e) {}
        }
      }
    });
  }

  function reportProgress() {
    if (!tmPlayer || typeof tmPlayer.getCurrentTime !== 'function') return;
    try {
      var cur = tmPlayer.getCurrentTime() || 0;
      var dur = tmPlayer.getDuration() || 0;
      TuneMindYT.onProgress(cur, dur);
    } catch (e) {}
  }

  function startTicker() {
    stopTicker();
    tmTicker = setInterval(reportProgress, 650);
  }

  function stopTicker() {
    if (tmTicker) {
      clearInterval(tmTicker);
      tmTicker = null;
    }
  }

  window.tmLoadVideo = function(videoId) {
    if (tmPlayer && typeof tmPlayer.loadVideoById === 'function') {
      tmPlayer.loadVideoById(videoId);
    }
  };
  window.tmPlayVideo = function() {
    if (tmPlayer && typeof tmPlayer.playVideo === 'function') {
      tmPlayer.playVideo();
    }
  };
  window.tmPauseVideo = function() {
    if (tmPlayer && typeof tmPlayer.pauseVideo === 'function') {
      tmPlayer.pauseVideo();
    }
  };
  window.tmSeekTo = function(sec) {
    if (tmPlayer && typeof tmPlayer.seekTo === 'function') {
      tmPlayer.seekTo(sec, true);
    }
  };
  window.tmStopVideo = function() {
    stopTicker();
    if (tmPlayer && typeof tmPlayer.stopVideo === 'function') {
      tmPlayer.stopVideo();
    }
  };

  var tag = document.createElement('script');
  tag.src = 'https://www.youtube.com/iframe_api';
  var first = document.getElementsByTagName('script')[0];
  first.parentNode.insertBefore(tag, first);
</script>
</body>
</html>
""".trimIndent()

/**
 * Allocates one reusable official YouTube IFrame WebView so toggling between the expanded
 * NowPlaying viewport and the docked mini-player never interrupts or reloads audio/video.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun rememberSharedYouTubeWebView(context: Context): WebView {
    val webView = remember(context.applicationContext) {
        WebView(context.applicationContext).apply {
            setBackgroundColor(AndroidColor.parseColor("#050507"))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    val isYoutubeFrame = url == "about:blank" ||
                        url.startsWith("https://www.youtube.com/") ||
                        url.startsWith("https://www.youtube-nocookie.com/") ||
                        url.startsWith("https://m.youtube.com/") ||
                        url.startsWith("https://accounts.google.com/") ||
                        url.startsWith(YOUTUBE_PLAYER_ORIGIN)
                    return !isYoutubeFrame
                }
            }
            addJavascriptInterface(TuneMindYouTubeEmbedController.buildJsBridge(), "TuneMindYT")
            loadDataWithBaseURL(
                YOUTUBE_PLAYER_ORIGIN,
                buildYouTubeEmbedHtml(),
                "text/html",
                "UTF-8",
                null
            )
            TuneMindYouTubeEmbedController.attachWebView(this)
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            TuneMindYouTubeEmbedController.detachWebView(webView)
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching { webView.destroy() }
        }
    }
    return webView
}

private fun attachSharedWebView(parent: ViewGroup, webView: WebView) {
    (webView.parent as? ViewGroup)?.let { existingParent ->
        if (existingParent !== parent) {
            existingParent.removeView(webView)
        }
    }
    if (webView.parent == null) {
        parent.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}

/**
 * Full visible official YouTube viewport rendered inside `NowPlayingSheet` when active.
 */
@Composable
fun TuneMindYouTubeExpandedViewport(
    webView: WebView,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF050507))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    attachSharedWebView(this, webView)
                }
            },
            update = { frame ->
                attachSharedWebView(frame, webView)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Compact visible official YouTube mini-player dock shown above TuneMind's navigation pill
 * whenever the YouTube player is active and `NowPlayingSheet` is minimized, keeping the
 * official YouTube player visibly on screen in strict accordance with YouTube Terms of Service.
 */
@Composable
fun TuneMindYouTubeMiniDock(
    webView: WebView,
    title: String,
    artist: String,
    isPlaying: Boolean,
    accentColor: Color,
    onTogglePlay: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101218).copy(alpha = 0.96f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clickable(onClick = onExpand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(156.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF050507))
            ) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            attachSharedWebView(this, webView)
                        }
                    },
                    update = { frame ->
                        attachSharedWebView(frame, webView)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "IN-APP YOUTUBE",
                    color = accentColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title.ifBlank { "YouTube Track" },
                    color = Color(0xFFF8FAFC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.16f))
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onExpand,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = "Expand player",
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close mini player",
                    tint = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
