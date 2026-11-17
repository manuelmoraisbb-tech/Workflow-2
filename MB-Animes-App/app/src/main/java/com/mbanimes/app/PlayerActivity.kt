package com.mbanimes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/** Player nativo para ver os episódios descarregados, sem internet. Lembra a posição. */
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var id = ""
    private var path = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_player)
        id = intent.getStringExtra(EXTRA_ID).orEmpty()
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (id.isEmpty() || path.isEmpty()) {
            finish()
            return
        }
        playerView = findViewById(R.id.playerView)
        findViewById<TextView>(R.id.playerTitle).text = intent.getStringExtra(EXTRA_TITLE)
        findViewById<View>(R.id.playerBack).setOnClickListener { finish() }

        val topBar = findViewById<View>(R.id.topBar)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                topBar.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        if (path.isEmpty()) return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val p = ExoPlayer.Builder(this).build()
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, error.message ?: "Erro ao reproduzir", Toast.LENGTH_LONG).show()
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        val saved = prefs().getLong("pos_$id", 0L)
        if (saved > 0) p.seekTo(saved)
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onStop() {
        player?.let {
            val pos = if (it.playbackState == Player.STATE_ENDED) 0L else it.currentPosition
            prefs().edit().putLong("pos_$id", pos).apply()
            it.release()
        }
        player = null
        playerView.player = null
        super.onStop()
    }

    private fun prefs() = getSharedPreferences("player", Context.MODE_PRIVATE)

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PATH = "path"

        fun intent(ctx: Context, id: String, title: String, path: String) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PATH, path)
    }
}
