package com.zerodelay.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Game picker: lists CazéTV lives that are AO VIVO right now. */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.gameList)
        status = findViewById(R.id.status)
        refresh = findViewById(R.id.refresh)
        list.layoutManager = LinearLayoutManager(this)
        refresh.setOnClickListener { load() }
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        status.text = getString(R.string.loading)
        status.visibility = View.VISIBLE
        list.visibility = View.GONE
        scope.launch {
            val games = withContext(Dispatchers.IO) {
                runCatching { YouTubeLive.listLiveGames() }.getOrDefault(emptyList())
            }
            if (games.isEmpty()) {
                status.text = getString(R.string.no_lives)
                status.visibility = View.VISIBLE
                refresh.requestFocus()
            } else {
                status.visibility = View.GONE
                list.visibility = View.VISIBLE
                list.adapter = GameAdapter(games) { open(it) }
                list.requestFocus()
            }
        }
    }

    private fun open(g: Game) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, g.videoId)
                .putExtra(PlayerActivity.EXTRA_TITLE, g.title)
        )
    }
}
