package com.example.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_DECIDE = "decide"
        const val ACTION_SET_PASSWORD = "set_password"

        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FROM = "from"
        const val EXTRA_ACCEPT = "accept"
        const val EXTRA_REMEMBER = "remember"
        const val EXTRA_PASSWORD = "password"

        private const val NOTIF_FG = 1
        private const val CH_FG = "fg"
        private const val CH_REQ = "req"

        // تُعيّنها الواجهة لعرض الحالة، وتُلغى عند إخفائها
        var onStatus: ((String) -> Unit)? = null
    }

    private lateinit var identity: Identity
    private var signal: SignalClient? = null
    private var sender: RtcSender? = null

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSharing()
            ACTION_DECIDE -> decide(intent)
            ACTION_SET_PASSWORD -> signal?.send(
                JSONObject().put("type", "set_password").put("password", intent.getStringExtra(EXTRA_PASSWORD) ?: "")
            )
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        ensureChannels()
        startForegroundCompat(buildNotification("جارٍ التشغيل..."))

        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: run {
            stopSelf(); return
        }

        sender?.release()
        sender = RtcSender(
            this,
            data,
            { to, payload -> signal?.send(JSONObject().put("type", "signal").put("to", to).put("data", payload)) },
            { emit("متصل بالخادم، الرمز: ${identity.deviceId ?: "..."}") }
        )
        connectSignal()
    }

    private fun connectSignal() {
        signal?.close()
        signal = SignalClient(Config.SERVER_URL, ::onServerMessage) {
            val msg = JSONObject().put("type", "register").put("secret", identity.secret)
            identity.deviceId?.let { msg.put("id", it) }
            signal?.send(msg)
        }
        signal?.connect()
        emit("جارٍ الاتصال بالخادم...")
    }

    private fun onServerMessage(m: JSONObject) {
        when (m.optString("type")) {
            "registered" -> {
                val id = m.getString("id")
                identity.saveId(id)
                emit("الرمز: $id")
            }
            "password_set" -> emit("تم ضبط كلمة المرور")
            "incoming" -> showRequest(m.getString("from"))
            "peer_connected" -> sender?.start(m.getString("peer"))
            "signal" -> {
                val from = m.getString("from")
                if (sender?.peer() == from) sender?.onRemoteSignal(m.getJSONObject("data"))
            }
            "hangup", "revoked" -> sender?.close()
            "error" -> emit("خطأ: ${m.optString("message")}")
        }
    }

    private fun decide(intent: Intent) {
        val from = intent.getStringExtra(EXTRA_FROM) ?: return
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        val remember = intent.getBooleanExtra(EXTRA_REMEMBER, false)

        signal?.send(
            JSONObject().put("type", "decision").put("to", from)
                .put("accept", accept).put("remember", remember)
        )
        if (accept) sender?.start(from)
        getSystemService(NotificationManager::class.java).cancel(from.hashCode())
    }

    private fun showRequest(from: String) {
        fun action(label: String, accept: Boolean, remember: Boolean, code: Int): NotificationCompat.Action {
            val i = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_DECIDE
                putExtra(EXTRA_FROM, from)
                putExtra(EXTRA_ACCEPT, accept)
                putExtra(EXTRA_REMEMBER, remember)
            }
            val pi = PendingIntent.getService(
                this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Action(0, label, pi)
        }

        val n = NotificationCompat.Builder(this, CH_REQ)
            .setContentTitle("طلب اتصال من الجهاز $from")
            .setContentText("هل تسمح بمشاركة الشاشة؟")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(action("قبول", true, false, from.hashCode() + 1))
            .addAction(action("قبول دائم", true, true, from.hashCode() + 2))
            .addAction(action("رفض", false, false, from.hashCode() + 3))
            .build()

        getSystemService(NotificationManager::class.java).notify(from.hashCode(), n)
    }

    private fun stopSharing() {
        sender?.release(); sender = null
        signal?.close(); signal = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun emit(text: String) {
        onStatus?.invoke(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_FG, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("مشاركة الشاشة")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_FG, n)
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FG, "حالة المشاركة", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REQ, "طلبات الاتصال", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onDestroy() {
        sender?.release(); sender = null
        signal?.close(); signal = null
        super.onDestroy()
    }
}
