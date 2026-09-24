package chat.mural.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import chat.mural.MainActivity

import android.accessibilityservice.AccessibilityButtonController
import android.os.Build

class AnclaAccessibilityService : AccessibilityService() {

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events
    }

    override fun onInterrupt() {
        Log.d(TAG, "AnclaAccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AnclaAccessibilityService connected and ready")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    Log.d(TAG, "Accessibility button clicked! Launching Ancla voice...")
                    launchAnclaVoice()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController?, available: Boolean) {
                    Log.d(TAG, "Accessibility button availability changed: $available")
                }
            }
            buttonCallback = callback
            controller.registerAccessibilityButtonCallback(callback)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && buttonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback!!)
        }
        if (instance == this) {
            instance = null
        }
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
