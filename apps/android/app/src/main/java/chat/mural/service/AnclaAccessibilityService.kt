package chat.mural.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import chat.mural.MainActivity

class AnclaAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to inspect context if requested
    }

    override fun onInterrupt() {
        Log.d(TAG, "AnclaAccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AnclaAccessibilityService connected and ready")
    }

    /**
     * Called when the accessibility button in the system navigation bar is clicked
     * or when triggered by accessibility gestures/shortcuts.
     */
    fun launchAnclaVoice() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "chat.ancla.VOICE_COMMAND"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("start_listening", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Ancla from accessibility", e)
        }
    }

    companion object {
        private const val TAG = "AnclaAccessibility"
        var instance: AnclaAccessibilityService? = null
            private set
    }
}
