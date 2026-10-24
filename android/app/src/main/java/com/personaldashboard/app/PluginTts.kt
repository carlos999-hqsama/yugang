package com.personaldashboard.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import org.json.JSONObject

/**
 * Host-owned Chinese TTS. Plugins cannot select endpoints or carry credentials. System TTS is
 * preferred; the free Baidu gettts route is used only when the system has no Chinese voice. Files
 * are plugin-private and revision-private. Same-UID process isolation is not an OS sandbox.
 */
class PluginTts(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private class TtsError(
        val code: String,
        message: String,
        val supportedFormats: List<String>? = null,
    ) : Exception(message)

    private class Session(
        val id: String,
        val pluginId: String,
        val revision: String,
        val cancellation: PluginCancellation,
    ) {
        val stopRequested = AtomicBoolean(false)
        val provider = AtomicReference<String?>()
        val stopHook = AtomicReference<(() -> Unit)?>()
        var detachParent: () -> Unit = {}
        val finished = CountDownLatch(1)
        val firstAudioMs = java.util.concurrent.atomic.AtomicLong(-1)
        val playbackDurationMs = java.util.concurrent.atomic.AtomicLong(-1)
    }

    private class SynthesisWatcher {
        val done = CountDownLatch(1)
        @Volatile var errorCode: Int? = null
    }

    private data class SystemEngine(
        val tts: TextToSpeech,
        val available: Boolean,
        val engine: String,
        val voice: android.speech.tts.Voice?,
    )

    private data class AudioResult(
        val file: File,
        val bytes: Long,
        val format: String,
        val durationMs: Long,
        val provider: String,
        val engine: String?,
        val voice: String?,
    )

    private data class Playback(
        val completed: Boolean,
        val firstAudioMs: Long?,
        val durationMs: Long?,
    )

    fun call(
        pluginId: String,
        request: JSONObject,
        revision: String,
        cancellation: PluginCancellation,
        receivedAt: Long = SystemClock.elapsedRealtime(),
        actionDeadlineNanos: Long? = null,
    ): JSONObject {
        PluginContract.id(pluginId)
        val op = request.optString("op", "status")
        val suppliedId = request.optString("id")
        val requestId = suppliedId.ifEmpty { UUID.randomUUID().toString() }
        val requestStartedAt = receivedAt
        return try {
            if (suppliedId.isNotEmpty())
                require(PluginTtsPolicy.validRequestId(suppliedId)) { "语音请求 id 无效" }
            val language = request.optString("lang", "zh-CN")
            require(language.equals("zh-CN", true) || language == "zh") { "当前仅支持中文 zh-CN" }
            val rate = request.optDouble("rate", 1.0).toFloat()
            val pitch = request.optDouble("pitch", 1.0).toFloat()
            require(rate.isFinite() && rate in 0.5f..2f && pitch.isFinite() && pitch in 0.5f..2f) {
                "rate/pitch 必须在 0.5–2 之间"
            }
            when (op) {
                "status" -> status(cancellation)
                "speak" -> {
                    val text = PluginTtsPolicy.validateText(request.getString("text"))
                    speak(
                        pluginId,
                        revision,
                        requestId,
                        text,
                        cancellation,
                        requestStartedAt,
                        rate,
                        pitch,
                        actionDeadlineNanos,
                    )
                }
                "synthesize" -> {
                    val text = PluginTtsPolicy.validateText(request.getString("text"))
                    val format =
                        PluginTtsPolicy.validateRequestedFormat(request.optString("format"))
                    synthesize(
                        pluginId,
                        revision,
                        requestId,
                        text,
                        format,
                        cancellation,
                        persistent = true,
                        rate,
                        pitch,
                    )
                }
                "stop" -> stop(pluginId, revision, request.getString("id"), cancellation)
                else -> throw TtsError("invalid_op", "不支持的 TTS 操作")
            }
        } catch (e: Exception) {
            failure(pluginId, revision, op, requestId, e)
        }
    }

    private fun status(cancellation: PluginCancellation): JSONObject {
        cancellation.check()
        synchronized(operationLock) {
            val system =
                try {
                    systemStatus(cancellation)
                } catch (e: Exception) {
                    JSONObject()
                        .put("available", false)
                        .put("error", error("system_unavailable", systemMessage(e)))
                }
            return PluginTtsPolicy.status(system)
        }
    }

    private fun speak(
        pluginId: String,
        revision: String,
        requestId: String,
        text: String,
        cancellation: PluginCancellation,
        requestStartedAt: Long,
        rate: Float,
        pitch: Float,
        actionDeadlineNanos: Long?,
    ): JSONObject {
        val session = register(requestId, pluginId, revision, cancellation)
        var audio: AudioResult? = null
        try {
            audio =
                synthesizeAudio(
                    pluginId,
                    revision,
                    requestId,
                    text,
                    null,
                    session.cancellation,
                    persistent = false,
                    rate,
                    pitch,
                )
            session.provider.set(audio.provider)
            if (actionDeadlineNanos != null) {
                val remainingMs = (actionDeadlineNanos - System.nanoTime()) / 1_000_000
                if (!PluginTtsPolicy.fitsActionPlayback(audio.durationMs, remainingMs)) {
                    throw TtsError("action_budget_exceeded", "语音时长超过插件动作剩余时间，请分段播放或在页面播放")
                }
            }
            val playback = play(audio.file, session, requestStartedAt)
            return JSONObject()
                .put("ok", playback.completed)
                .put("op", "speak")
                .put("rate", rate)
                .put("pitch", pitch)
                .put(
                    "providerSpeed",
                    if (audio.provider == PluginTtsPolicy.PROVIDER_FALLBACK)
                        (rate * 3).toInt().coerceIn(1, 9)
                    else JSONObject.NULL,
                )
                .put("id", requestId)
                .put("provider", audio.provider)
                .apply {
                    if (audio.engine != null) put("engine", audio.engine)
                    if (audio.voice != null) put("voice", audio.voice)
                }
                .put("firstAudioMs", playback.firstAudioMs ?: JSONObject.NULL)
                .put(
                    "firstAudioAt",
                    playback.firstAudioMs?.let { requestStartedAt + it } ?: JSONObject.NULL,
                )
                .put("clock", "elapsedRealtimeMs")
                .put(
                    "measurement",
                    if (playback.firstAudioMs != null) "media-timestamp" else "unmeasured",
                )
                .put("durationMs", audio.durationMs)
                .put("playbackMs", playback.durationMs ?: JSONObject.NULL)
                .apply {
                    if (!playback.completed) put("error", error("cancelled", "播放已停止"))
                    else if (playback.firstAudioMs == null)
                        put("error", error("audio_start_unmeasured", "已播放，但设备未提供音频首帧时间"))
                }
        } finally {
            audio?.file?.delete()
            finish(session)
        }
    }

    private fun synthesize(
        pluginId: String,
        revision: String,
        requestId: String,
        text: String,
        requestedFormat: String?,
        cancellation: PluginCancellation,
        persistent: Boolean,
        rate: Float,
        pitch: Float,
    ): JSONObject {
        val session = register(requestId, pluginId, revision, cancellation)
        try {
            val audio =
                synthesizeAudio(
                    pluginId,
                    revision,
                    requestId,
                    text,
                    requestedFormat,
                    session.cancellation,
                    persistent,
                    rate,
                    pitch,
                )
            val fileObject =
                JSONObject()
                    .put("id", requestId)
                    .put("name", "${requestId}.${audio.format}")
                    .put("path", relativeFile(revision, audio.file.name))
                    .put("openPath", openPath(pluginId, revision, audio.file.name))
                    .put("url", mediaUrl(pluginId, revision, audio.file.name))
                    .put("bytes", audio.bytes)
                    .put("format", audio.format)
                    .put("durationMs", audio.durationMs)
            return JSONObject()
                .put("ok", true)
                .put("op", "synthesize")
                .put("rate", rate)
                .put("pitch", pitch)
                .put(
                    "providerSpeed",
                    if (audio.provider == PluginTtsPolicy.PROVIDER_FALLBACK)
                        (rate * 3).toInt().coerceIn(1, 9)
                    else JSONObject.NULL,
                )
                .put("id", requestId)
                .put("provider", audio.provider)
                .apply {
                    if (audio.engine != null) put("engine", audio.engine)
                    if (audio.voice != null) put("voice", audio.voice)
                }
                .put("file", fileObject)
                .put("path", fileObject.getString("openPath"))
                .put("bytes", audio.bytes)
                .put("format", audio.format)
                .put("durationMs", audio.durationMs)
        } finally {
            finish(session)
        }
    }

    private fun stop(
        pluginId: String,
        revision: String,
        id: String,
        cancellation: PluginCancellation,
    ): JSONObject {
        cancellation.check()
        if (!PluginTtsPolicy.validRequestId(id))
            return JSONObject()
                .put("ok", true)
                .put("stopped", false)
                .put("id", id)
                .put("error", error("not_found_or_not_allowed", "未找到可停止的当前插件音频"))
        val session =
            synchronized(registryLock) {
                val key = sessionKey(pluginId, revision, id)
                sessions[key]
                    ?: run {
                        val now = SystemClock.elapsedRealtime()
                        preempted.entries.removeAll { now - it.value > 120000 }
                        preempted[key] = now
                        while (preempted.size > 128) preempted.remove(preempted.keys.first())
                        null
                    }
            }
        if (session == null)
            return JSONObject()
                .put("ok", true)
                .put("id", id)
                .put("stopped", false)
                .put("pendingCancelled", true)
        if (
            session.pluginId != pluginId ||
                session.revision != revision ||
                !session.stopRequested.compareAndSet(false, true)
        ) {
            return JSONObject()
                .put("ok", true)
                .put("stopped", false)
                .put("id", id)
                .put("error", error("not_found_or_not_allowed", "未找到可停止的当前插件音频"))
        }
        session.cancellation.cancel()
        session.stopHook.get()?.let { main.post(it) }
        runCatching { session.finished.await(3, TimeUnit.SECONDS) }
        return JSONObject().put("ok", true).put("stopped", true).put("id", id)
    }

    private fun register(
        id: String,
        pluginId: String,
        revision: String,
        cancellation: PluginCancellation,
    ): Session {
        val child = PluginCancellation()
        val session = Session(id, pluginId, revision, child)
        session.detachParent = cancellation.onCancel { child.cancel() }
        val key = sessionKey(pluginId, revision, id)
        try {
            synchronized(registryLock) {
                cancellation.check()
                check(
                    preempted[key]?.let { SystemClock.elapsedRealtime() - it <= 120000 } != true,
                ) {
                    "语音请求已取消"
                }
                require(sessions.putIfAbsent(key, session) == null) { "语音请求 id 已使用" }
            }
        } catch (e: Exception) {
            session.detachParent()
            throw e
        }
        try {
            child.check()
        } catch (e: Exception) {
            finish(session)
            throw e
        }
        return session
    }

    private fun finish(session: Session) {
        sessions.remove(sessionKey(session.pluginId, session.revision, session.id), session)
        session.detachParent()
        session.cancellation.cancel()
        session.finished.countDown()
        session.stopHook.set(null)
        File(cacheDirectory(appContext, session.pluginId, session.revision), "${session.id}.tmp")
            .delete()
        File(revisionDirectory(appContext, session.pluginId, session.revision), "${session.id}.tmp")
            .delete()
    }

    private fun synthesizeAudio(
        pluginId: String,
        revision: String,
        requestId: String,
        text: String,
        requestedFormat: String?,
        cancellation: PluginCancellation,
        persistent: Boolean,
        rate: Float,
        pitch: Float,
    ): AudioResult {
        synchronized(operationLock) {
            var system: SystemEngine? = null
            try {
                system = openSystemEngine()
                cancellation.check()
                if (system.available) {
                    check(system.tts.setSpeechRate(rate) == TextToSpeech.SUCCESS) {
                        "system_rate_unsupported"
                    }
                    check(system.tts.setPitch(pitch) == TextToSpeech.SUCCESS) {
                        "system_pitch_unsupported"
                    }
                    sessions[sessionKey(pluginId, revision, requestId)]
                        ?.provider
                        ?.set(PluginTtsPolicy.PROVIDER_SYSTEM)
                    if (requestedFormat != null && requestedFormat != PluginTtsPolicy.SYSTEM_FORMAT)
                        throw TtsError(
                            "unsupported_format",
                            "当前系统中文语音只输出 WAV",
                            listOf(PluginTtsPolicy.SYSTEM_FORMAT),
                        )
                    val temp = tempFile(pluginId, revision, requestId, persistent, cancellation)
                    val needsNetwork = system.voice?.let {
                        runCatching { it.isNetworkConnectionRequired }.getOrNull()
                    }
                    if (needsNetwork != false) {
                        showNetworkNotice(
                            if (needsNetwork == true) "在线语音：朗读文字将发送给系统语音服务。"
                            else "系统语音可能联网，朗读文字可能发送给语音服务。",
                        )
                    }
                    systemSynthesis(system, text, temp, requestId, cancellation)
                    return finishAudio(
                        temp,
                        persistent,
                        pluginId,
                        revision,
                        PluginTtsPolicy.PROVIDER_SYSTEM,
                        system.engine,
                        system.voice?.name,
                        requestedFormat,
                        cancellation,
                    )
                }
            } catch (e: TtsError) {
                throw e
            } catch (e: Exception) {
                cancellation.check()
                // Opening or querying the system engine failed; only then is the system route
                // absent.
                if (system != null) throw TtsError("system_synthesis_failed", systemMessage(e))
            } finally {
                system?.tts?.let { tts -> main.post { runCatching { tts.shutdown() } } }
            }

            if (requestedFormat == PluginTtsPolicy.SYSTEM_FORMAT)
                throw TtsError(
                    "unsupported_format",
                    "系统中文语音不可用，免费回退只输出 MP3",
                    listOf(PluginTtsPolicy.FALLBACK_FORMAT),
                )
            sessions[sessionKey(pluginId, revision, requestId)]
                ?.provider
                ?.set(PluginTtsPolicy.PROVIDER_FALLBACK)
            require(pitch == 1f) { "免费回退不支持自定义 pitch" }
            cancellation.check()
            showNetworkNotice("在线语音：朗读文字将发送给百度语音服务。")
            val body = fallbackDownload(text, cancellation, rate)
            val temp = tempFile(pluginId, revision, requestId, persistent, cancellation)
            temp.writeBytes(body.bytes)
            return finishAudio(
                temp,
                persistent,
                pluginId,
                revision,
                PluginTtsPolicy.PROVIDER_FALLBACK,
                null,
                "baidu-z",
                requestedFormat,
                cancellation,
            )
        }
    }

    private fun showNetworkNotice(message: String) {
        main.post {
            // Shared across requests; streaming replies must not queue a toast for every chunk.
            if (shownNetworkNotices.add(message))
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openSystemEngine(): SystemEngine {
        val engineReady = java.util.concurrent.CompletableFuture<TextToSpeech>()
        val ready = java.util.concurrent.CompletableFuture<Int>()
        main.post {
            try {
                val tts = TextToSpeech(appContext) { status -> ready.complete(status) }
                engineReady.complete(tts)
            } catch (e: Exception) {
                engineReady.completeExceptionally(e)
            }
        }
        val tts = engineReady.get(5, TimeUnit.SECONDS)
        try {
            val status = ready.get(5, TimeUnit.SECONDS)
            check(status == TextToSpeech.SUCCESS) { "system_init_failed" }

            val chinese =
                tts.voices
                    .orEmpty()
                    .filter { it.locale.language.equals("zh", ignoreCase = true) }
                    .sortedWith(
                        compareBy(
                            { runCatching { it.isNetworkConnectionRequired }.getOrDefault(true) },
                            { it.locale.country != "CN" },
                            { it.name },
                        ),
                    )
            var selected: android.speech.tts.Voice? = null
            var available = false
            val voice = chinese.firstOrNull()
            if (voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS) {
                selected = voice
                available = true
            }
            if (!available) {
                val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                selected =
                    tts.voice?.takeIf {
                        it.locale.language.equals("zh", ignoreCase = true)
                    }
                available =
                    language in
                        setOf(
                            TextToSpeech.LANG_AVAILABLE,
                            TextToSpeech.LANG_COUNTRY_AVAILABLE,
                            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
                        ) && selected != null
            }
            return SystemEngine(
                tts,
                available,
                tts.defaultEngine ?: "system",
                selected,
            )
        } catch (e: Exception) {
            main.post { runCatching { tts.shutdown() } }
            throw e
        }
    }

    private fun systemStatus(cancellation: PluginCancellation): JSONObject {
        cancellation.check()
        val engine = openSystemEngine()
        try {
            val voice = engine.voice
            val networkRequired = voice?.let {
                runCatching { it.isNetworkConnectionRequired }.getOrNull()
            }
            return JSONObject()
                .put("available", engine.available)
                .put("engine", engine.engine)
                .put("voice", voice?.name ?: JSONObject.NULL)
                .put("language", voice?.locale?.toString() ?: "zh-CN")
                .put("requiresNetwork", networkRequired != false)
                .put("offlineConfirmed", networkRequired == false)
        } finally {
            main.post { runCatching { engine.tts.shutdown() } }
        }
    }

    private fun systemSynthesis(
        engine: SystemEngine,
        text: String,
        output: File,
        requestId: String,
        cancellation: PluginCancellation,
    ) {
        val watcher = SynthesisWatcher()
        synthesisCallbacks[requestId] = watcher
        engine.tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // onDone is the file-completion boundary; onStart is not proof that bytes are
                    // ready.
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == requestId) watcher.done.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == requestId) {
                        watcher.done.countDown()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == requestId) {
                        watcher.errorCode = errorCode
                        watcher.done.countDown()
                    }
                }
            },
        )
        try {
            val detach = cancellation.onCancel {
                runCatching { engine.tts.stop() }
                watcher.done.countDown()
            }
            cancellation.check()
            val accepted =
                engine.tts.synthesizeToFile(text, null, output, requestId) == TextToSpeech.SUCCESS
            check(accepted) { "system_rejected" }
            try {
                check(watcher.done.await(35, TimeUnit.SECONDS)) { "system_timeout" }
                cancellation.check()
                watcher.errorCode?.let {
                    throw TtsError("system_synthesis_failed", "系统语音合成失败 ($it)")
                }
                check(output.isFile && output.length() > 44) { "system_empty_output" }
            } finally {
                detach()
            }
        } finally {
            synthesisCallbacks.remove(requestId)
        }
    }

    private fun fallbackDownload(
        text: String,
        cancellation: PluginCancellation,
        rate: Float,
    ): PluginTtsPolicy.Audio {
        val url =
            okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("fanyi.baidu.com")
                .addPathSegment("gettts")
                .addQueryParameter("lan", "zh")
                .addQueryParameter("text", text)
                .addQueryParameter("spd", (rate * 3).toInt().coerceIn(1, 9).toString())
                .addQueryParameter("source", "web")
                .build()
        val call = fallbackClient.newCall(Request.Builder().url(url).get().build())
        val detach = cancellation.onCancel { call.cancel() }
        try {
            cancellation.check()
            call.execute().use { response ->
                cancellation.check()
                check(response.isSuccessful) { "fallback_http_${response.code}" }
                val body =
                    readBounded(response.body?.byteStream(), PluginTtsPolicy.MAX_FALLBACK_BYTES)
                return PluginTtsPolicy.parseFallback(response.header("Content-Type"), body)
            }
        } catch (e: IOException) {
            cancellation.check()
            throw TtsError("fallback_network", "免费语音服务暂不可用")
        } finally {
            detach()
        }
    }

    private fun readBounded(source: java.io.InputStream?, max: Int): ByteArray {
        val input = source ?: error("fallback_invalid_response")
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            check(output.size() <= max) { "fallback_too_large" }
        }
        return output.toByteArray()
    }

    private fun tempFile(
        pluginId: String,
        revision: String,
        requestId: String,
        persistent: Boolean,
        cancellation: PluginCancellation,
    ): File =
        PluginPlatform.withActiveRevision(appContext, pluginId, revision) {
            cancellation.check()
            val parent =
                if (persistent) revisionDirectory(appContext, pluginId, revision)
                else cacheDirectory(appContext, pluginId, revision)
            parent.mkdirs()
            check(parent.isDirectory) { "storage_unavailable" }
            File(parent, "$requestId.tmp")
        }

    private fun finishAudio(
        temp: File,
        persistent: Boolean,
        pluginId: String,
        revision: String,
        provider: String,
        engine: String?,
        voice: String?,
        requestedFormat: String?,
        cancellation: PluginCancellation,
    ): AudioResult {
        try {
            cancellation.check()
            val bytes = temp.readBytes()
            check(bytes.size <= MAX_AUDIO_BYTES) { "audio_too_large" }
            val actual = PluginTtsPolicy.detectFormat(bytes)
            if (actual == null) throw TtsError("unknown_audio_format", "系统返回了未知音频格式")
            if (requestedFormat != null && requestedFormat != actual)
                throw TtsError(
                    "unsupported_format",
                    "当前语音线路不支持请求的格式",
                    listOf(actual),
                )
            cancellation.check()
            val duration = probeDuration(temp)
            return PluginPlatform.withActiveRevision(appContext, pluginId, revision) {
                synchronized(cancellation) {
                    cancellation.check()
                    val destination =
                        if (persistent) {
                            check(
                                reserveSpace(
                                    revisionDirectory(appContext, pluginId, revision),
                                    bytes.size.toLong(),
                                    temp,
                                ),
                            ) {
                                "storage_limit"
                            }
                            File(
                                revisionDirectory(appContext, pluginId, revision),
                                "${temp.nameWithoutExtension}.$actual",
                            )
                        } else {
                            File(temp.parentFile, "${temp.nameWithoutExtension}.$actual")
                        }
                    Files.move(
                        temp.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    AudioResult(
                        destination,
                        destination.length(),
                        actual,
                        duration,
                        provider,
                        engine,
                        voice,
                    )
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun probeDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            check(duration != null && duration > 0) { "duration_unknown" }
            return duration
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun play(file: File, session: Session, requestStartedAt: Long): Playback {
        val audioManager =
            appContext.getSystemService(AudioManager::class.java) ?: error("audio_unavailable")
        val player = MediaPlayer()
        val prepared = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val firstAudioAt = AtomicReference<Long?>()
        val endedAt = AtomicReference<Long?>()
        val interrupted = AtomicBoolean(false)
        val focusAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(focusAttributes)
                .setOnAudioFocusChangeListener { change ->
                    if (
                        change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        interrupted.set(true)
                        main.post {
                            runCatching { player.stop() }
                            done.countDown()
                        }
                    }
                }
                .build()
        var focusGranted = false
        session.stopHook.set {
            interrupted.set(true)
            runCatching { player.stop() }
            done.countDown()
        }
        val detach =
            session.cancellation.onCancel {
                interrupted.set(true)
                main.post {
                    runCatching { player.stop() }
                    done.countDown()
                }
            }
        try {
            session.cancellation.check()
            focusGranted =
                audioManager.requestAudioFocus(focusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(focusGranted) { "audio_focus_denied" }
            player.setAudioAttributes(focusAttributes)
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                try {
                    check(!interrupted.get()) { "audio_interrupted" }
                    it.start()
                } catch (e: Exception) {
                    failure.set("player_start_failed")
                    done.countDown()
                }
                prepared.countDown()
            }
            player.setOnCompletionListener {
                val ended = SystemClock.elapsedRealtime()
                endedAt.set(ended)
                firstAudioAt.get()?.let { started ->
                    session.playbackDurationMs.set(ended - started)
                }
                done.countDown()
            }
            player.setOnErrorListener { _, what, extra ->
                failure.set("player_error_${what}_$extra")
                prepared.countDown()
                done.countDown()
                true
            }
            player.prepareAsync()
            check(prepared.await(5, TimeUnit.SECONDS)) { "player_prepare_timeout" }
            session.cancellation.check()
            val deadline = SystemClock.elapsedRealtime() + 120000
            while (!done.await(10, TimeUnit.MILLISECONDS)) {
                session.cancellation.check()
                check(SystemClock.elapsedRealtime() < deadline) { "player_timeout" }
                if (firstAudioAt.get() == null) {
                    // Presentation clock anchor advances only when media frames actually play.
                    // Never substitute MediaPlayer.start() or a page timestamp for the first frame.
                    val stamp = runCatching { player.timestamp }.getOrNull()
                    if (stamp != null && stamp.anchorMediaTimeUs > 0 && stamp.mediaClockRate > 0) {
                        val now = SystemClock.elapsedRealtime()
                        val onset =
                            now + (stamp.anchorSystemNanoTime - System.nanoTime()) / 1000000 -
                                (stamp.anchorMediaTimeUs / (1000.0 * stamp.mediaClockRate)).toLong()
                        if (
                            onset in requestStartedAt..now &&
                                firstAudioAt.compareAndSet(null, onset)
                        )
                            session.firstAudioMs.set(onset - requestStartedAt)
                    }
                }
            }
            failure.get()?.let { throw TtsError(it, it) }
            val first = firstAudioAt.get()
            val end = endedAt.get()
            val duration = if (first != null && end != null) end - first else null
            return Playback(!interrupted.get(), first?.let { it - requestStartedAt }, duration)
        } catch (e: Exception) {
            if (
                session.cancellation.cancelled || session.stopRequested.get() || interrupted.get()
            ) {
                val first = firstAudioAt.get()
                val end = endedAt.get() ?: SystemClock.elapsedRealtime()
                first?.let { started -> session.playbackDurationMs.set(end - started) }
                return Playback(
                    false,
                    first?.let { it - requestStartedAt },
                    first?.let { end - it },
                )
            }
            throw e
        } finally {
            detach()
            session.stopHook.set(null)
            runCatching { player.stop() }
            runCatching { player.release() }
            if (focusGranted) audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun failure(
        pluginId: String,
        revision: String,
        op: String,
        id: String,
        exception: Exception,
    ): JSONObject {
        val cancellation =
            exception is InterruptedException ||
                exception is java.util.concurrent.TimeoutException ||
                exception.message == "system_timeout" ||
                exception.message == "player_timeout"
        val cancellationState =
            exception.message?.let { it.contains("插件操作已取消") || it == "语音请求已取消" } == true
        val code =
            when {
                cancellationState -> "cancelled"
                cancellation -> "timeout"
                exception is TtsError -> exception.code
                exception is IllegalArgumentException -> "invalid_request"
                else -> "internal_error"
            }
        val result =
            JSONObject()
                .put("ok", false)
                .put("op", op)
                .put("id", id)
                .put("error", error(code, userMessage(exception)))
        val activeSession =
            sessions.values.firstOrNull {
                it.id == id && it.pluginId == pluginId && it.revision == revision
            }
        activeSession?.provider?.get()?.let { result.put("provider", it) }
        activeSession?.let { session ->
            if (session.firstAudioMs.get() >= 0)
                result.put("firstAudioMs", session.firstAudioMs.get())
            if (session.playbackDurationMs.get() >= 0)
                result.put("durationMs", session.playbackDurationMs.get())
        }
        (exception as? TtsError)?.supportedFormats?.let {
            result.put("supportedFormats", org.json.JSONArray(it))
        }
        return result
    }

    private fun userMessage(exception: Exception): String =
        when (exception) {
            is TtsError -> exception.message ?: "语音服务失败"
            is IllegalArgumentException -> exception.message ?: "请求参数无效"
            is InterruptedException -> "语音操作已取消"
            is java.util.concurrent.TimeoutException -> "语音操作超时"
            else -> "语音服务失败，结果未确认"
        }

    private fun systemMessage(exception: Exception): String =
        if (exception is IllegalArgumentException || exception is TtsError)
            exception.message ?: "系统中文语音不可用"
        else "系统中文语音不可用"

    private fun error(code: String, message: String): JSONObject =
        PluginTtsPolicy.error(code, message)

    companion object {
        private const val MAX_AUDIO_BYTES = 5 * 1024 * 1024
        private const val MAX_FILES_PER_REVISION = 16
        private const val MAX_REVISION_BYTES = 16L * 1024 * 1024
        private val operationLock = Any()
        // Accessed only on the main thread, and reset when the app process restarts.
        private val shownNetworkNotices = mutableSetOf<String>()
        private val storeLock = Any()
        private val registryLock = Any()
        private val preempted = linkedMapOf<String, Long>()
        private val sessions = ConcurrentHashMap<String, Session>()
        private val synthesisCallbacks = ConcurrentHashMap<String, SynthesisWatcher>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val fallbackClient = PluginTtsPolicy.fallbackClient()

        fun mediaUrl(pluginId: String, revision: String, fileName: String): String =
            "https://plugin-${PluginContract.id(pluginId)}.pd.local/$revision/__host_tts/$fileName"

        private fun sessionKey(pluginId: String, revision: String, id: String): String =
            "${PluginContract.id(pluginId)}\u0000$revision\u0000$id"

        private fun relativeFile(revision: String, fileName: String): String =
            "media/tts/$revision/$fileName"

        private fun openPath(pluginId: String, revision: String, fileName: String): String =
            "/PLUGINS/${PluginContract.id(pluginId)}/media/tts/$revision/$fileName"

        private fun revisionDirectory(context: Context, pluginId: String, revision: String): File =
            File(context.filesDir, "PLUGINS/${PluginContract.id(pluginId)}/media/tts/$revision")

        private fun cacheDirectory(context: Context, pluginId: String, revision: String): File =
            File(context.cacheDir, "plugin-tts/${PluginContract.id(pluginId)}/$revision")

        private fun reserveSpace(directory: File, incoming: Long, exclude: File): Boolean =
            synchronized(storeLock) {
                if (incoming > MAX_AUDIO_BYTES || incoming > MAX_REVISION_BYTES) return false
                directory.mkdirs()
                if (!directory.isDirectory) return false
                val files =
                    directory.listFiles()?.filter { it.isFile && it != exclude } ?: return false
                var total = files.sumOf { it.length() } + incoming
                var count = files.size + 1
                val ordered = files.sortedBy { it.lastModified() }
                var index = 0
                while (
                    (count > MAX_FILES_PER_REVISION || total > MAX_REVISION_BYTES) &&
                        index < ordered.size
                ) {
                    val oldBytes = ordered[index].length()
                    if (!ordered[index].delete()) return false
                    total -= oldBytes
                    count--
                    index++
                }
                count <= MAX_FILES_PER_REVISION && total <= MAX_REVISION_BYTES
            }

        /**
         * Called after old runs are cancelled; returns false when private media could not be
         * reclaimed.
         */
        fun invalidateRevision(
            context: Context,
            pluginId: String,
            revision: String?,
        ): Boolean {
            PluginContract.id(pluginId)
            val revisions =
                if (revision == null) {
                    File(context.filesDir, "PLUGINS/$pluginId/media/tts")
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory }
                } else {
                    listOf(revisionDirectory(context, pluginId, revision))
                }
            val cache = File(context.cacheDir, "plugin-tts/$pluginId")
            val revisionCache = revision?.let { File(cache, it) }
            val targets = revisions + listOfNotNull(revisionCache ?: cache)
            return targets.all { !it.exists() || it.deleteRecursively() }
        }

        fun stopPlugin(pluginId: String) {
            sessions.values
                .filter { it.pluginId == pluginId }
                .forEach { session ->
                    session.stopRequested.set(true)
                    session.cancellation.cancel()
                    session.stopHook.get()?.let { mainHandler.post(it) }
                }
        }

        fun file(context: Context, pluginId: String, revision: String, fileName: String): File? {
            if (!Regex("^[0-9a-f-]{36}\\.(wav|mp3)$").matches(fileName)) return null
            return revisionDirectory(context, pluginId, revision).resolve(fileName).takeIf {
                it.isFile
            }
        }
    }
}
