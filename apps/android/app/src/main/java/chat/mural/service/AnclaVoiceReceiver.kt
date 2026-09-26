package chat.mural.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-registered BroadcastReceiver allowing external services (such as Bio-Gesture Control)
 * to activate Ancla AI Assistant entirely in the background without launching MainActivity.
 */
class AnclaVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        Log.i("AnclaVoiceReceiver", "External voice broadcast received: $action")
        val service = AnclaAccessibilityService.instance
        if (service != null) {
            when (action) {
                "chat.ancla.ACTION_START_LISTENING",
                "chat.mural.ACTION_START_LISTENING" -> service.startVoice()
                "chat.ancla.ACTION_STOP_LISTENING",
                "chat.mural.ACTION_STOP_LISTENING" -> service.stopVoice()
                else -> service.toggleVoice()
            }
        } else {
            Log.w("AnclaVoiceReceiver", "AnclaAccessibilityService instance is null. Is accessibility enabled?")
        }
    }
}
