package engineer.cookie.soundrecorder

import android.app.Application
import engineer.cookie.soundrecorder.service.RecordingNotifications
import engineer.cookie.soundrecorder.share.ShareManager

class SoundRecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.createChannel(this)
        ShareManager.cleanupCache(this)
    }
}
