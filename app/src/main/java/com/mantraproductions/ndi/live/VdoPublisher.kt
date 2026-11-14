package com.mantraproductions.ndi.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.view.Surface
import com.mantraproductions.ndi.Trace
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * v197, GO LIVE: the camera publishes into VDO.Ninja itself, as the browser page ?push=<id>&password=<pw> does, so the
 * band site's unchanged viewer (?view=<id>&password=<pw>) shows it. Written from docs/VDO_NINJA_SIGNALLING.md; no code
 * of any VDO.Ninja client is in here.
 *
 * One websocket to the hosted handshake server; one PeerConnection per viewer UUID, each with the same video track
 * (frames the GPU stage draws into [surface]) and audio track (the phone's or the Sony's microphone, Opus, stereo when
 * the source is), and a data channel answering ping with pong. Every call and callback runs on one thread.
 */
class VdoPublisher(private val ctx: Context, private val listener: (State) -> Unit) {

    enum class Phase { OFF, CONNECTING, LIVE }
    data class State(val phase: Phase, val viewers: Int, val note: String)

    @Volatile var state = State(Phase.OFF, 0, ""); private set

    /** The GPU stage draws the picture here (null until [start] made it). Its size is [width] x [height]. */
    @Volatile var surface: Surface? = null; private set
    var width = 1920; private set
    var height = 1080; private set

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "live") }
    private val http = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var ws: WebSocket? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    private var streamId = ""
    private var password = ""
    private var wire = ""
    private var running = false
    private var reconnects = 0
    private var ticker: ScheduledFuture<*>? = null
    @Volatile var framesOut = 0L; private set

    private inner class Peer(val uuid: String, val session: String) {
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        var offerSent = false
        var remoteSet = false
        val localQueue = ArrayList<IceCandidate>()
        val remoteQueue = ArrayList<IceCandidate>()
        var missedPongs = 0
        var connected = false
        var everConnected = false
        var lostAt = 0L
        val born = System.currentTimeMillis()
    }
    private val peers = HashMap<String, Peer>()

    // --- start and stop ------------------------------------------------------------------------------------------------

    /**
     * Opens the factory and the picture's surface, then the websocket. [mic] picks the input (the Sony's USB audio),
     * [stereo] asks for two channels. Returns at once; the state follows through [listener].
     */
    fun start(id: String, pw: String, w: Int = 1920, h: Int = 1080, fps: Int = 30, maxKbps: Int = 4000,
              mic: AudioDeviceInfo? = null, stereo: Boolean = false) {
        worker.execute {
            if (running) stopNow("restart")
            running = true
            streamId = id; password = pw
            wire = VdoCrypto.wireId(id, pw)
            width = w; height = h
            this.maxKbps = maxKbps
            set(Phase.CONNECTING, "opening")
            runCatching { build(fps, mic, stereo) }.onFailure {
                Trace.fault("live", it)
                set(Phase.OFF, "WebRTC did not start: ${it.javaClass.simpleName}")
                running = false
                return@execute
            }
            iceServers = fetchIce()
            connect()
            ticker = worker.scheduleWithFixedDelay({ tick() }, 3, 3, TimeUnit.SECONDS)
            // v209 SOUND LINK: the WebRTC recorder wrapped as soon as it records (and again if it is made again)
            wrapTicker = worker.scheduleWithFixedDelay({ MacAudioRecord.install(adm) }, 1, 1, TimeUnit.SECONDS)
        }
    }

    /**
     * v211, SANTOSHI RELAY: publishes by WHIP to [url] (the relay's door, from the site) as [user] / [key]. [viewers] is asked every
     * 15 s on a side thread (the site's watching count; the relay itself does not tell the phone). Returns at once.
     */
    fun startWhip(url: String, user: String, key: String, w: Int = 1920, h: Int = 1080, fps: Int = 30, maxKbps: Int = 4000,
                  mic: AudioDeviceInfo? = null, stereo: Boolean = false, viewers: (() -> Int?)? = null) {
        worker.execute {
            if (running) stopNow("restart")
            running = true
            whipUrl = url; whipAuth = okhttp3.Credentials.basic(user, key); whipCount = viewers; relayViewers = 0
            streamId = "relay"; password = ""
            width = w; height = h
            this.maxKbps = maxKbps
            set(Phase.CONNECTING, "opening")
            runCatching { build(fps, mic, stereo) }.onFailure {
                Trace.fault("live", it)
                set(Phase.OFF, "WebRTC did not start: ${it.javaClass.simpleName}")
                running = false
                return@execute
            }
            iceServers = listOf(PeerConnection.IceServer.builder(listOf("stun:stun.cloudflare.com:3478")).createIceServer())
            whipConnect()
            ticker = worker.scheduleWithFixedDelay({ whipTick() }, 3, 3, TimeUnit.SECONDS)
            // v209 SOUND LINK: the WebRTC recorder wrapped as soon as it records (and again if it is made again)
            wrapTicker = worker.scheduleWithFixedDelay({ MacAudioRecord.install(adm) }, 1, 1, TimeUnit.SECONDS)
        }
    }

    /** How many peer connections the phone holds now: 1 on the relay, one per viewer on VDO.Ninja (the adb "live" line). */
    val peerCount: Int get() = peers.size

    /** True while this publisher sends to the relay (WHIP), false for VDO.Ninja. */
    val relay: Boolean get() = whipUrl != null

    fun stop() = worker.execute { stopNow("stopped") }

    // --- v211: WHIP to the relay ----------------------------------------------------------------------------------------

    private var whipUrl: String? = null
    private var whipAuth = ""
    private var whipResource: String? = null
    private var whipCount: (() -> Int?)? = null
    private var whipTries = 0
    private var whipTicks = 0
    @Volatile private var relayViewers = 0
    private val side = Executors.newSingleThreadExecutor { Thread(it, "live-side") }

    private fun whipConnect() {
        if (!running || whipUrl == null) return
        val f = factory ?: return
        peers.values.forEach { closePeer(it, false) }; peers.clear()
        whipDelete()
        val p = Peer("relay", "whip")
        peers[p.uuid] = p
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = f.createPeerConnection(cfg, whipObserver(p)) ?: run { peers.clear(); return }
        p.pc = pc
        videoTrack?.let { pc.addTrack(it, listOf("mantra")) }
        audioTrack?.let { pc.addTrack(it, listOf("mantra")) }
        for (t in pc.transceivers) {
            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                onlyH264(f, t)
                val sn = t.sender
                val params = sn.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                params.encodings.forEach { e -> e.maxBitrateBps = maxKbps * 1000; e.maxFramerate = 30 }
                sn.parameters = params
            }
        }
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) = worker.execute {
                val sdp = SessionDescription(d.type, LiveLink.opusStereo(d.description))
                pc.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = worker.execute {
                        // the whole offer at once (no trickle): wait for the gathering, at most 2 s
                        worker.schedule({ if (peers[p.uuid] === p && !p.offerSent) whipPost(p) }, 2, TimeUnit.SECONDS)
                    }
                    override fun onSetFailure(e: String?) { Trace.refused("live", "relay local offer: $e") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) { Trace.refused("live", "relay offer: $e") }
        }, MediaConstraints())
        Trace.step("live: relay offer made (one connection)")
        set(Phase.CONNECTING, "relay")
    }

    private fun whipPost(p: Peer) {
        val pc = p.pc ?: return
        val offer = pc.localDescription?.description ?: return
        p.offerSent = true
        val url = whipUrl ?: return
        val req = Request.Builder().url(url)
            .header("Authorization", whipAuth)
            .post(offer.toRequestBody("application/sdp".toMediaTypeOrNull()))
            .build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = worker.execute {
                Trace.refused("live", "relay WHIP: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                whipRetry(p)
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code
                val loc = response.header("Location")
                val body = runCatching { response.body?.string()?.take(64_000) }.getOrNull().orEmpty()
                response.close()
                worker.execute {
                    if (peers[p.uuid] !== p) return@execute
                    if (code != 201 || !body.startsWith("v=")) {
                        Trace.refused("live", "relay WHIP: HTTP $code ${body.take(120).replace('\n', ' ')}")
                        if (code == 401 || code == 403) { set(Phase.CONNECTING, "relay refused the key"); }
                        whipRetry(p); return@execute
                    }
                    whipResource = loc?.let { runCatching { java.net.URI(url).resolve(it).toString() }.getOrNull() }
                    val ans = SessionDescription(SessionDescription.Type.ANSWER, LiveLink.opusStereo(body))
                    pc.setRemoteDescription(object : Sdp() {
                        override fun onSetSuccess() = worker.execute { p.remoteSet = true; Trace.step("live: relay WHIP 201, answer set") }
                        override fun onSetFailure(e: String?) { Trace.refused("live", "relay answer: $e"); worker.execute { whipRetry(p) } }
                    }, ans)
                }
            }
        })
    }

    private fun whipRetry(p: Peer) {
        if (!running || peers[p.uuid] !== p) return
        closePeer(p, false); peers.remove(p.uuid)
        whipTries++
        val wait = (3L * whipTries).coerceAtMost(30L)
        Trace.refused("live", "relay: again in $wait s")
        set(Phase.CONNECTING, "reconnecting to the relay")
        worker.schedule({ if (running && peers.isEmpty()) whipConnect() }, wait, TimeUnit.SECONDS)
    }

    /** Ends the relay session (DELETE on the WHIP resource), off the worker thread, best effort. */
    private fun whipDelete() {
        val r = whipResource ?: return
        whipResource = null
        val auth = whipAuth
        runCatching {
            http.newCall(Request.Builder().url(r).header("Authorization", auth).delete().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: Response) { response.close() }
            })
        }
    }

    private fun whipTick() {
        val p = peers["relay"]
        val now = System.currentTimeMillis()
        if (p != null) {
            if (!p.everConnected && now - p.born > 20_000) { Trace.refused("live", "relay: not connected in 20 s"); whipRetry(p); return }
            if (p.everConnected && !p.connected && p.lostAt > 0 && now - p.lostAt > 8_000) { Trace.refused("live", "relay: lost for 8 s"); whipRetry(p); return }
        }
        if (++whipTicks % 5 == 1) whipCount?.let { c -> side.execute { runCatching { c() }.getOrNull()?.let { n -> worker.execute { relayViewers = n; refresh() } } } }
    }

    /** The relay takes H.264 only (its HLS fallback and recording cannot carry VP8): H.264 (+ its retransmission) offered, nothing else. */
    private fun onlyH264(f: PeerConnectionFactory, t: RtpTransceiver) = runCatching {
        val all = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val h = all.filter { it.name.equals("H264", true) && it.parameters["packetization-mode"] == "1" }
            .sortedBy { if (it.parameters["profile-level-id"]?.startsWith("42e0") == true) 0 else 1 }
        if (h.isEmpty()) { preferH264(f, t); return@runCatching }
        t.setCodecPreferences(h + all.filter { it.name.equals("rtx", true) || it.name.equals("red", true) || it.name.equals("ulpfec", true) })
    }

    private fun whipObserver(p: Peer) = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) worker.execute { if (peers[p.uuid] === p && !p.offerSent) whipPost(p) }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = worker.execute {
            if (peers[p.uuid] !== p) return@execute
            Trace.step("live: relay $s")
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { p.connected = true; p.everConnected = true; p.lostAt = 0L; whipTries = 0 }
                PeerConnection.PeerConnectionState.FAILED -> { p.connected = false; whipRetry(p) }
                PeerConnection.PeerConnectionState.DISCONNECTED -> { p.connected = false; p.lostAt = System.currentTimeMillis() }
                else -> {}
            }
            refresh()
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private var maxKbps = 4000

    private fun build(fps: Int, mic: AudioDeviceInfo?, stereo: Boolean) {
        if (factory == null) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .createInitializationOptions())
            val egl = EglBase.create().also { eglBase = it }
            val audio = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseStereoInput(stereo)
                .setUseStereoOutput(false)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                // v209 SOUND LINK: 1.3.10 never calls AudioRecordDataCallback; the recorder is wrapped instead (MacAudioRecord)
                .createAudioDeviceModule()
            adm = audio
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audio)
                // hardware H.264 first where the phone has it; VP8 (software) is always there
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
        }
        mic?.let { d -> runCatching { adm?.setPreferredInputDevice(d) } }
        val f = factory!!
        val src = f.createVideoSource(false).also { videoSource = it }
        src.adaptOutputFormat(width, height, fps)
        val h = SurfaceTextureHelper.create("live-frames", eglBase!!.eglBaseContext).also { helper = it }
        h.setTextureSize(width, height)
        src.capturerObserver.onCapturerStarted(true)
        h.startListening { frame -> framesOut++; src.capturerObserver.onFrameCaptured(frame) }
        surface = Surface(h.surfaceTexture)
        videoTrack = f.createVideoTrack("mantra-video", src)
        // music, not a call: no echo cancelling, no gain riding, no noise gate
        val c = MediaConstraints().apply {
            for (k in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter"))
                mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
        }
        audioSource = f.createAudioSource(c)
        audioTrack = f.createAudioTrack("mantra-audio", audioSource)
    }

    private var wrapTicker: java.util.concurrent.ScheduledFuture<*>? = null

    private fun stopNow(why: String) {
        ticker?.cancel(false); ticker = null
        wrapTicker?.cancel(false); wrapTicker = null
        for (p in peers.values) closePeer(p, sendBye = true)
        peers.clear()
        whipDelete(); whipUrl = null; whipCount = null; whipTries = 0
        ws?.close(1000, "bye"); ws = null
        running = false
        surface?.release(); surface = null
        helper?.stopListening()
        videoSource?.capturerObserver?.onCapturerStopped()
        videoTrack?.dispose(); videoTrack = null
        audioTrack?.dispose(); audioTrack = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        helper?.dispose(); helper = null
        set(Phase.OFF, why)
    }

    /** For onDestroy: everything, the factory too. */
    fun release() = worker.execute {
        stopNow("closed")
        factory?.dispose(); factory = null
        adm?.release(); adm = null
        eglBase?.release(); eglBase = null
        worker.shutdown()
        side.shutdown()
    }

    // --- STUN / TURN, once per GO LIVE (section 4) --------------------------------------------------------------------

    private fun fetchIce(): List<PeerConnection.IceServer> {
        val out = arrayListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun.cloudflare.com:3478")).createIceServer()
        )
        runCatching {
            val c = URL("https://turnservers.vdo.ninja/").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000
            val body = c.inputStream.use { it.readBytes().decodeToString().take(32_000) }
            c.disconnect()
            val list = JSONObject(body).optJSONArray("servers") ?: JSONArray()
            var udp = 0; var tcp = 0
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val isUdp = s.optBoolean("udp", false)
                if (isUdp && udp >= 2 || !isUdp && tcp >= 1) continue
                if (isUdp) udp++ else tcp++
                val urls = s.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: listOf(s.optString("url")).filter { it.isNotEmpty() }
                if (urls.isEmpty()) continue
                out.add(PeerConnection.IceServer.builder(urls).setUsername(s.optString("username"))
                    .setPassword(s.optString("credential")).createIceServer())
            }
            Trace.step("live: ${out.size - 1} TURN servers")
        }.onFailure { Trace.refused("live", "no TURN list (${it.javaClass.simpleName}: ${it.message?.take(120)}); STUN only") }
        return out
    }

    // --- the handshake server (sections 1, 2, 6) ----------------------------------------------------------------------

    private fun connect() {
        if (!running) return
        val req = Request.Builder().url("wss://wss.vdo.ninja:443").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = worker.execute {
                if (webSocket !== ws) return@execute
                reconnects = 0
                send(JSONObject().put("request", "seed").put("streamID", wire))
                Trace.step("live: seeded (${streamId.take(4)}…)")
                if (peers.isEmpty()) set(Phase.CONNECTING, "waiting for a viewer") else refresh()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = worker.execute {
                if (webSocket === ws) runCatching { onServer(JSONObject(text)) }.onFailure { Trace.fault("live msg", it) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = worker.execute { dropped(webSocket, "closed $code") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                worker.execute { dropped(webSocket, t.javaClass.simpleName + (t.message?.let { ": " + it.take(120) } ?: "") + (response?.let { " HTTP " + it.code } ?: "")) }
        })
    }

    private fun dropped(w: WebSocket, why: String) {
        if (w !== ws || !running) return
        ws = null
        reconnects++
        val wait = (5L * reconnects).coerceAtMost(30L)
        Trace.refused("live", "handshake server $why, again in $wait s")
        if (peers.values.none { it.connected }) set(Phase.CONNECTING, "reconnecting")
        worker.schedule({ connect() }, wait, TimeUnit.SECONDS)
    }

    private fun send(j: JSONObject) { ws?.send(j.toString()) }

    private fun onServer(m: JSONObject) {
        val req = m.optString("request")
        if (req == "ping") { send(JSONObject().put("request", "pong").put("id", m.opt("id"))); return }
        val uuid = m.optString("UUID")
        if (req == "play" || req.contains("offerSDP")) {
            val sid = m.optString("streamID")
            if (uuid.isEmpty() || sid.isNotEmpty() && sid != wire) return
            viewerAsks(uuid)
            return
        }
        if (uuid.isEmpty()) return
        val p = peers[uuid] ?: return
        if (m.optBoolean("bye", false)) { closePeer(p, false); peers.remove(uuid); refresh(); return }
        val session = m.optString("session")
        if (session.isNotEmpty() && session != p.session) return
        if (m.has("description")) onAnswer(p, m)
        if (m.has("candidates") || m.has("candidate")) onCandidates(p, m)
    }

    private fun decryptField(m: JSONObject, field: String): String =
        if (m.has("vector")) VdoCrypto.decrypt(m.getString(field), m.getString("vector"), password)
        else m.get(field).toString()

    // --- one viewer ---------------------------------------------------------------------------------------------------

    private fun viewerAsks(uuid: String) {
        peers[uuid]?.let { old ->
            val age = System.currentTimeMillis() - old.born
            if (old.connected || age < 8000) return        // asked again while connecting: let it finish
            closePeer(old, false); peers.remove(uuid)
        }
        val f = factory ?: return
        val p = Peer(uuid, VdoCrypto.randomHex(10))
        peers[uuid] = p
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(cfg, observer(p)) ?: run { peers.remove(uuid); return }
        p.pc = pc
        videoTrack?.let { pc.addTrack(it, listOf("mantra")) }
        audioTrack?.let { pc.addTrack(it, listOf("mantra")) }
        for (t in pc.transceivers) {
            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                preferH264(f, t)
                val s = t.sender
                val params = s.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
                params.encodings.forEach { e -> e.maxBitrateBps = maxKbps * 1000; e.maxFramerate = 30 }
                s.parameters = params
            }
        }
        p.dc = pc.createDataChannel("sendChannel", DataChannel.Init()).also { dc -> dc.registerObserver(channel(p, dc)) }
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) = worker.execute {
                val sdp = SessionDescription(d.type, LiveLink.opusStereo(d.description))
                pc.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = worker.execute { sendOffer(p, sdp) }
                    override fun onSetFailure(e: String?) { Trace.refused("live", "local offer: $e") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) { Trace.refused("live", "offer: $e") }
        }, MediaConstraints())
        Trace.step("live: viewer ${uuid.take(6)} asks, offer made")
        refresh()
    }

    /** H.264 (packetization-mode 1) first when the phone has an encoder for it, then VP8, then the rest. */
    private fun preferH264(f: PeerConnectionFactory, t: RtpTransceiver) = runCatching {
        val all = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        fun rank(c: org.webrtc.RtpCapabilities.CodecCapability): Int = when {
            c.name.equals("H264", true) && c.parameters["packetization-mode"] == "1" &&
                c.parameters["profile-level-id"]?.startsWith("42e0") == true -> 0
            c.name.equals("H264", true) && c.parameters["packetization-mode"] == "1" -> 1
            c.name.equals("VP8", true) -> 2
            c.name.equals("H264", true) -> 3
            else -> 4
        }
        t.setCodecPreferences(all.sortedBy { rank(it) })
    }

    private fun sendOffer(p: Peer, d: SessionDescription) {
        val (ct, iv) = VdoCrypto.encrypt(JSONObject().put("type", "offer").put("sdp", d.description).toString(), password)
        send(JSONObject().put("UUID", p.uuid).put("session", p.session).put("streamID", wire)
            .put("description", ct).put("vector", iv))
        p.offerSent = true
        if (p.localQueue.isNotEmpty()) { sendCandidates(p, p.localQueue.toList()); p.localQueue.clear() }
    }

    private fun sendCandidates(p: Peer, list: List<IceCandidate>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("candidate", c.sdp).put("sdpMLineIndex", c.sdpMLineIndex).put("sdpMid", c.sdpMid))
        val (ct, iv) = VdoCrypto.encrypt(a.toString(), password)
        send(JSONObject().put("UUID", p.uuid).put("session", p.session).put("type", "local")
            .put("candidates", ct).put("vector", iv))
    }

    private fun onAnswer(p: Peer, m: JSONObject) {
        val d = JSONObject(decryptField(m, "description"))
        if (d.optString("type") != "answer") return
        val pc = p.pc ?: return
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, LiveLink.opusStereo(d.getString("sdp")))
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = worker.execute {
                p.remoteSet = true
                p.remoteQueue.forEach { pc.addIceCandidate(it) }
                p.remoteQueue.clear()
                Trace.step("live: viewer ${p.uuid.take(6)} answered")
            }
            override fun onSetFailure(e: String?) { Trace.refused("live", "answer: $e") }
        }, sdp)
    }

    private fun onCandidates(p: Peer, m: JSONObject) {
        val field = if (m.has("candidates")) "candidates" else "candidate"
        val raw = decryptField(m, field).trim()
        val items = if (raw.startsWith("[")) JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            else listOf(JSONObject(raw))
        for (c in items) {
            val s = c.optString("candidate")
            if (s.isEmpty()) continue
            val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), s)
            if (p.remoteSet) p.pc?.addIceCandidate(ice) else p.remoteQueue.add(ice)
        }
    }

    private fun closePeer(p: Peer, sendBye: Boolean) {
        if (sendBye) runCatching { p.dc?.send(DataChannel.Buffer(ByteBuffer.wrap("{\"bye\":true}".toByteArray()), false)) }
        runCatching { p.dc?.unregisterObserver(); p.dc?.close(); p.dc?.dispose() }
        runCatching { p.pc?.dispose() }
        p.dc = null; p.pc = null
    }

    // --- heartbeat and the count (section 6) --------------------------------------------------------------------------

    private fun tick() {
        val now = System.currentTimeMillis()
        for (p in peers.values.toList()) {
            if (p.connected) {
                p.missedPongs++
                runCatching {
                    p.dc?.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("ping", now.toString()).toString().toByteArray()), false))
                }
                if (p.missedPongs > 10) { Trace.refused("live", "viewer ${p.uuid.take(6)}: no heartbeat"); closePeer(p, false); peers.remove(p.uuid) }
            } else if (p.everConnected && p.lostAt > 0 && now - p.lostAt > 15_000) {
                // v205: a viewer that blinked to DISCONNECTED gets 15 s to come back (was dropped at once as "never connected")
                Trace.refused("live", "viewer ${p.uuid.take(6)} lost for 15 s"); closePeer(p, false); peers.remove(p.uuid)
            } else if (!p.everConnected && now - p.born > 30_000) {
                Trace.refused("live", "viewer ${p.uuid.take(6)} never connected"); closePeer(p, false); peers.remove(p.uuid)
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!running) return
        val n = peers.values.count { it.connected }
        if (whipUrl != null) { if (n > 0) set(Phase.LIVE, "relay") else set(Phase.CONNECTING, "relay"); return }
        if (n > 0) set(Phase.LIVE, "") else set(Phase.CONNECTING, if (ws == null) "reconnecting" else "waiting for a viewer")
    }

    private fun set(phase: Phase, note: String) {
        val n = if (whipUrl != null) relayViewers else peers.values.count { it.connected }
        val s = State(phase, if (phase == Phase.LIVE) n else 0, note)
        if (s != state) { state = s; listener(s) }
    }

    // --- observers ----------------------------------------------------------------------------------------------------

    private fun observer(p: Peer) = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = worker.execute {
            if (c.sdp.contains(" tcp ")) return@execute      // as the reference client does: UDP and relay only
            if (p.offerSent) sendCandidates(p, listOf(c)) else p.localQueue.add(c)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = worker.execute {
            Trace.step("live: viewer ${p.uuid.take(6)} $s")
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { p.connected = true; p.everConnected = true; p.lostAt = 0L; p.missedPongs = 0 }
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> {
                    if (peers[p.uuid] === p) { closePeer(p, false); peers.remove(p.uuid) }
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> { p.connected = false; p.lostAt = System.currentTimeMillis() }
                else -> {}
            }
            refresh()
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun channel(p: Peer, dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) {}
        override fun onStateChange() {
            if (dc.state() == DataChannel.State.OPEN) worker.execute {
                runCatching {
                    val info = JSONObject().put("info", JSONObject().put("label", "Mantra Manual Camera").put("muted", false))
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(info.toString().toByteArray()), false))
                }
            }
        }
        override fun onMessage(b: DataChannel.Buffer) {
            val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
            if (bytes.size > 64_000) return
            worker.execute {
                val m = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return@execute
                when {
                    m.has("ping") -> runCatching {
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("pong", m.get("ping")).toString().toByteArray()), false))
                    }
                    m.has("pong") -> p.missedPongs = 0
                    m.optBoolean("bye", false) -> { if (peers[p.uuid] === p) { closePeer(p, false); peers.remove(p.uuid); refresh() } }
                }
            }
        }
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
