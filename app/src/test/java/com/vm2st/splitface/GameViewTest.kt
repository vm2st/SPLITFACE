package com.vm2st.splitface

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import android.view.MotionEvent
import android.widget.Button
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 36, 37])
@LooperMode(LooperMode.Mode.PAUSED)
class GameViewTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var view: GameView

    @Before fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        activity = controller.get()
        view = activity.findViewById(com.vm2st.splitface.R.id.game_view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    }
    @After fun tearDown() { controller.pause().stop().destroy() }

    private fun state() = view.saveGameState()
    private fun balls() = state().getFloatArray("balls")!!
    private fun down(x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
    private fun pointerDown(firstX: Float, firstY: Float, secondX: Float, secondY: Float) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply { x = firstX; y = firstY; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = secondX; y = secondY; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(0, 0,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun manifestRequestsLandscapeByDefault() {
        val info = activity.packageManager.getActivityInfo(ComponentName(activity, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, info.screenOrientation)
    }
    @Test fun resetButtonKeepsOriginalArrowInBothStates() {
        val reset: Button = activity.findViewById(R.id.reset_button)
        assertTrue(reset.text.toString().startsWith("↻"))
        reset.performClick()
        assertTrue(reset.text.toString().startsWith("↻"))
        assertTrue(view.isResetPending)
    }

    @Test fun activityInflatesAndRenders() {
        assertTrue(view.width > 0)
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
        assertNotNull(view.contentDescription)
    }
    @Test fun pauseAndResumeButtonsMatchState() {
        val button: Button = activity.findViewById(R.id.pause_button)
        button.performClick(); assertTrue(view.isPaused)
        assertEquals(activity.getString(R.string.resume), button.text.toString())
        button.performClick(); assertFalse(view.isPaused)
    }
    @Test fun resetNeedsSecondClickAndUpdatesLabel() {
        val reset: Button = activity.findViewById(R.id.reset_button)
        reset.performClick(); assertTrue(view.isResetPending)
        assertEquals(activity.getString(R.string.confirm_reset), reset.text.toString())
        reset.performClick(); assertFalse(view.isResetPending); assertFalse(view.isPaused)
    }
    @Test fun simultaneousPlayersCanBothScore() {
        val b = balls()
        down(b[2], b[3])
        pointerDown(b[2], b[3], b[6], b[7])
        assertEquals(1, state().getInt("leftScore"))
        assertEquals(1, state().getInt("rightScore"))
    }
    @Test fun pointerDownDoesNotReprocessOldFingerAfterCooldown() {
        val b = balls()
        down(b[2], b[3])
        // Expire only the cooldown, keeping both balls stationary for this input regression.
        val saved = state()
        val values = saved.getFloatArray("balls")!!
        values[10] = 0f
        saved.putFloatArray("balls", values)
        view.restoreGameState(saved)
        pointerDown(b[2], b[3], b[6], b[7])
        assertEquals(1, state().getInt("leftScore"))
        assertEquals(1, state().getInt("rightScore"))
    }
    @Test fun backgroundActivityRejectsTaps() {
        val b = balls(); controller.pause()
        down(b[2], b[3]); assertEquals(0, state().getInt("leftScore"))
        controller.resume()
    }
    @Test fun gameStateSurvivesActivityRecreation() {
        val b = balls(); down(b[2], b[3]); view.requestReset()
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().restoreInstanceState(saved)
            .resume().visible().windowFocusChanged(true)
        activity = controller.get()
        view = activity.findViewById(R.id.game_view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertEquals(1, state().getInt("leftScore"))
        assertTrue(view.isPaused); assertTrue(view.isResetPending)
    }
    @Test fun tappingFieldCancelsResetWithoutScoring() {
        val b = balls(); down(b[2], b[3]); view.requestReset()
        down(b[2], b[3])
        assertFalse(view.isResetPending); assertFalse(view.isPaused)
        assertEquals(1, state().getInt("leftScore"))
    }
    @Test fun cancelAndMoveDoNotScore() {
        val b = balls()
        for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 0, action, b[2], b[3], 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        assertEquals(0, state().getInt("leftScore"))
    }

    @Test fun backgroundFreezesPhysicsAndResumeDoesNotCatchUpGap() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(100))
        controller.pause()
        val stopped = balls()
        looper.idleFor(Duration.ofSeconds(30))
        assertArrayEquals(stopped, balls(), 0f)
        controller.resume()
        looper.idleFor(Duration.ofMillis(32))
        val resumed = balls()
        assertTrue(kotlin.math.abs(resumed[2] - stopped[2]) < 40f)
    }
    @Test fun pausedScreenDoesNotAdvancePhysics() {
        view.togglePause()
        val before = balls()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertArrayEquals(before, balls(), 0f)
    }

    @Test fun malformedSavedStateIsIgnored() {
        val before = balls()
        view.restoreGameState(Bundle().apply { putInt("version", 1); putFloatArray("balls", floatArrayOf(Float.NaN)) })
        assertArrayEquals(before, balls(), 0f)
    }
}
