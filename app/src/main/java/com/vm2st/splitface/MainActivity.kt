package com.vm2st.splitface

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button

class MainActivity : Activity() {
    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gameView = findViewById(R.id.game_view)
        val pause: Button = findViewById(R.id.pause_button)
        val reset: Button = findViewById(R.id.reset_button)
        gameView.onStateChanged = {
            pause.setText(if (gameView.isPaused) R.string.resume else R.string.pause)
            reset.setText(if (gameView.isResetPending) R.string.confirm_reset else R.string.reset)
        }
        pause.setOnClickListener { gameView.togglePause() }
        reset.setOnClickListener { gameView.requestReset() }
        savedInstanceState?.getBundle("game")?.let(gameView::restoreGameState)
        configureInsets()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        gameView.setHostActive(true)
    }

    override fun onPause() {
        gameView.setHostActive(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("game", gameView.saveGameState())
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION") // Required compatibility paths for Android 6–15.
    private fun configureInsets() {
        val root: View = findViewById(R.id.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                root.setOnApplyWindowInsetsListener { view, insets ->
                    val cutout = insets.displayCutout
                    view.setPadding(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                        maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                        maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                        maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
                    insets
                }
            }
        }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
