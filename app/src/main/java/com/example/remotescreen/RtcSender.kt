package com.example.remotescreen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

// الجهاز المُشارَك: يلتقط الشاشة ويرسلها إلى الطرف المعتمد عبر WebRTC
class RtcSender(
    private val context: Context,
    private val projectionData: Intent,
    private val sendSignal: (to: String, payload: JSONObject) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var peerId: String? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun peer(): String? = peerId

    fun start(peer: String) {
        close()
        peerId = peer

        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }

        pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(peer, JSONObject().put("kind", "ice").put("ice", JSONObject()
                    .put("sdpMid", candidate.sdpMid ?: "")
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)))
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) close()
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        // مسار الفيديو من التقاط الشاشة
        val surface = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        helper = surface
        val videoSource = factory.createVideoSource(true)
        source = videoSource
        val screen = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { close() }
        })
        capturer = screen
        screen.initialize(surface, context, videoSource.capturerObserver)
        screen.startCapture(1280, 720, 15)

        pc?.addTrack(factory.createVideoTrack("screen0", videoSource), listOf("stream0"))

        // المُشارِك هو من يبدأ العرض بإرسال العرض (offer)
        pc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                pc?.setLocalDescription(SdpObserverAdapter(), desc)
                sendSignal(peer, JSONObject().put("kind", "sdp").put("sdp", JSONObject()
                    .put("type", desc.type.canonicalForm())
                    .put("sdp", desc.description)))
            }
        }, MediaConstraints())
    }

    // استقبال الإجابة (answer) ومرشحات ICE من الطرف الآخر
    fun onRemoteSignal(data: JSONObject) {
        val connection = pc ?: return
        when (data.getString("kind")) {
            "sdp" -> {
                val s = data.getJSONObject("sdp")
                val desc = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(s.getString("type")),
                    s.getString("sdp")
                )
                connection.setRemoteDescription(SdpObserverAdapter(), desc)
            }
            "ice" -> {
                val c = data.getJSONObject("ice")
                connection.addIceCandidate(
                    IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                )
            }
        }
    }

    fun close() {
        try { capturer?.stopCapture() } catch (_: Exception) { }
        capturer?.dispose(); capturer = null
        source?.dispose(); source = null
        helper?.dispose(); helper = null
        pc?.close(); pc = null
        if (peerId != null) {
            peerId = null
            onClosed()
        }
    }

    fun release() {
        close()
        factory.dispose()
        eglBase.release()
    }
}
