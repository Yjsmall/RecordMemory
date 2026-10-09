package dev.local.record.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.local.record.MainActivity

/** Widget entry that starts capture without showing the library. */
class RecordToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecordingService.command(this, RecordingService.START)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(RecordingService.START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
