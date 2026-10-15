package com.vm2st.splitface

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class GameEngineTest {
    private fun game(w: Int = 1920, h: Int = 1080) = GameEngine().apply { resize(w, h) }
    private fun assertValid(g: GameEngine) {
        for (b in listOf(g.leftBall, g.rightBall)) {
            assertTrue(listOf(b.x, b.y, b.vx, b.vy, b.radius).all { it.isFinite() })
            assertTrue(b.radius > 0f)
            assertTrue("x=${b.x}", b.x >= b.radius - .001f && b.x <= g.width - b.radius + .001f)
            assertTrue("y=${b.y}", b.y >= b.radius - .001f && b.y <= g.height - b.radius + .001f)
        }
    }
    private fun hitLeft(g: GameEngine) = g.hit(g.leftBall.x, g.leftBall.y)

    @Test fun uninitializedGameIsSafe() {
        val g = GameEngine()
        g.advance(1f); g.reset()
        assertNull(g.hit(0f, 0f))
        g.resize(0, 10); g.resize(10, -1)
        assertFalse(g.ready)
    }
    @Test fun initialPositionsAreValid() { assertValid(game()) }
    @Test fun bothPlayersCanScoreInSameFrame() {
        val g = game()
        assertNotNull(hitLeft(g))
        assertNotNull(g.hit(g.rightBall.x, g.rightBall.y))
        assertEquals(1, g.leftScore); assertEquals(1, g.rightScore)
    }
    @Test fun heldOrRepeatedTapCannotBypassCooldown() {
        val g = game()
        hitLeft(g)
        assertNull(hitLeft(g))
        g.advance(.13f)
        assertNotNull(hitLeft(g))
        assertEquals(2, g.leftScore)
    }
    @Test fun missesDoNotConsumeCooldown() {
        val g = game()
        assertNull(g.hit(0f, 0f)); assertNotNull(hitLeft(g))
    }
    @Test fun foulResetsOnlyOffendingPlayer() {
        val g = game()
        hitLeft(g); g.hit(g.rightBall.x, g.rightBall.y)
        g.advance(.2f)
        g.rightBall.x = g.width * .25f
        g.rightBall.y = g.height * .25f
        val result = g.hit(g.rightBall.x, g.rightBall.y)!!
        assertTrue(result.foul); assertTrue(result.leftPlayer)
        assertEquals(0, g.leftScore); assertEquals(1, g.rightScore)
    }
    @Test fun rightPlayerFoulIsSymmetric() {
        val g = game()
        g.hit(g.rightBall.x, g.rightBall.y)
        g.advance(.2f)
        g.leftBall.x = g.width * .75f; g.leftBall.y = g.height * .25f
        assertTrue(g.hit(g.leftBall.x, g.leftBall.y)!!.foul)
        assertEquals(0, g.rightScore)
    }
    @Test fun resetClearsAllTransientStateAndCooldowns() {
        val g = game()
        hitLeft(g); g.requestReset(); g.requestReset()
        assertEquals(0, g.leftScore); assertEquals(0, g.rightScore)
        assertFalse(g.paused); assertFalse(g.resetPending)
        assertEquals(0f, g.leftDistortion, 0f)
        assertNotNull(hitLeft(g))
    }
    @Test fun resetRequiresConfirmationAndCanBeCancelled() {
        val g = game()
        hitLeft(g)
        assertFalse(g.requestReset())
        assertTrue(g.paused); assertTrue(g.resetPending)
        assertEquals(1, g.leftScore)
        g.resume()
        assertFalse(g.resetPending); assertEquals(1, g.leftScore)
    }
    @Test fun pauseFreezesPhysicsAndRejectsHits() {
        val g = game(); g.togglePause()
        val before = g.snapshot()
        g.advance(1f)
        assertNull(hitLeft(g))
        assertEquals(before, g.snapshot())
    }
    @Test fun pauseToggleCancelsPendingReset() {
        val g = game(); g.requestReset(); g.togglePause()
        assertFalse(g.paused); assertFalse(g.resetPending)
    }
    @Test fun coincidentBallsNeverProduceNaN() {
        val g = game()
        g.leftBall.x = 960f; g.rightBall.x = 960f
        g.leftBall.y = 540f; g.rightBall.y = 540f
        g.leftBall.vx = 0f; g.rightBall.vx = 0f
        g.leftBall.vy = 0f; g.rightBall.vy = 0f
        g.advance(1f / 60)
        assertValid(g)
        assertTrue(abs(g.leftBall.x - g.rightBall.x) >= g.leftBall.radius * 2f - .001f)
    }
    @Test fun approachingBallsBounce() {
        val g = game()
        g.leftBall.x = 950f; g.rightBall.x = 970f
        g.leftBall.y = 540f; g.rightBall.y = 540f
        g.leftBall.vx = 100f; g.rightBall.vx = -100f
        g.leftBall.vy = 0f; g.rightBall.vy = 0f
        g.advance(.001f)
        assertTrue(g.leftBall.vx < 0); assertTrue(g.rightBall.vx > 0)
    }
    @Test fun separatingBallsAreNotReversedByOverlap() {
        val g = game()
        g.leftBall.x = 950f; g.rightBall.x = 970f
        g.leftBall.vx = -100f; g.rightBall.vx = 100f
        g.advance(.001f)
        assertTrue(g.leftBall.vx < 0); assertTrue(g.rightBall.vx > 0)
    }
    @Test fun boundaryCollisionPointsInward() {
        val g = game()
        g.leftBall.x = 0f; g.leftBall.y = 0f
        g.leftBall.vx = -100f; g.leftBall.vy = -100f
        g.advance(.01f)
        assertTrue(g.leftBall.vx >= 0); assertTrue(g.leftBall.vy >= 0)
        assertValid(g)
    }
    @Test fun centreHitRetainsDirection() {
        val g = game()
        val angleBefore = g.leftBall.vy / g.leftBall.vx
        hitLeft(g)
        assertEquals(angleBefore, g.leftBall.vy / g.leftBall.vx, .00001f)
    }
    @Test fun speedLimitScalesWithScreenSize() {
        val g = game(960, 540)
        repeat(30) {
            g.leftBall.x = 100f; g.leftBall.y = 270f
            hitLeft(g); g.advance(.13f)
        }
        assertTrue(hypot(g.leftBall.vx, g.leftBall.vy) <= 300.001f)
    }
    @Test fun distortionExpiresWithElapsedTime() {
        val g = game()
        g.leftBall.x = 959f
        val hit = hitLeft(g)!!
        assertTrue(hit.distortion)
        assertTrue(g.rightDistortion > 0f)
        g.advance(.2f); assertTrue(g.rightDistortion > 0f)
        g.advance(.11f); assertEquals(0f, g.rightDistortion, 0f)
    }
    @Test fun resizePreservesScorePauseAndRelativePositions() {
        val g = game(); hitLeft(g); g.togglePause()
        val oldX = g.leftBall.x / g.width
        g.resize(960, 540)
        assertEquals(1, g.leftScore); assertTrue(g.paused)
        assertEquals(oldX, g.leftBall.x / g.width, .0001f)
        assertValid(g)
    }
    @Test fun tinyAndPortraitSurfacesAreSafe() {
        for ((w,h) in listOf(1 to 1, 1 to 500, 500 to 1, 320 to 240, 240 to 320)) {
            val g = game(w,h)
            repeat(500) { g.advance(1f / 60) }
            assertValid(g)
        }
    }
    @Test fun frameRateDoesNotChangeTravelDistance() {
        val a = game(); val b = game()
        repeat(30) { a.advance(1f / 30) }
        repeat(120) { b.advance(1f / 120) }
        assertEquals(a.leftBall.x, b.leftBall.x, .02f)
        assertEquals(a.leftBall.y, b.leftBall.y, .02f)
    }
    @Test fun invalidAndHugeTimeStepsAreBounded() {
        val g = game(); val before = g.snapshot()
        for (dt in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) g.advance(dt)
        assertEquals(before, g.snapshot())
        g.advance(Float.MAX_VALUE)
        assertValid(g)
        assertTrue(abs(g.leftBall.x - before.left.x) <= 252f * .25f + 1)
    }
    @Test fun invalidTouchesAreIgnored() {
        val g = game()
        for ((x,y) in listOf(Float.NaN to 0f, 0f to Float.POSITIVE_INFINITY, -1f to 1f, 2000f to 1f))
            assertNull(g.hit(x,y))
        assertEquals(0, g.leftScore)
    }
    @Test fun snapshotRoundTripPreservesGame() {
        val g = game(); hitLeft(g); g.requestReset()
        val s = g.snapshot()
        val restored = GameEngine()
        assertTrue(restored.restore(s)); assertEquals(s, restored.snapshot())
        restored.resize(960, 540)
        assertEquals(1, restored.leftScore); assertTrue(restored.resetPending)
        assertValid(restored)
    }
    @Test fun snapshotDoesNotAliasMutableBalls() {
        val g = game(); val s = g.snapshot(); g.advance(.1f)
        assertNotEquals(s.left.x, g.leftBall.x)
    }
    @Test fun corruptSnapshotsAreRejectedWithoutChangingState() {
        val g = game(); val before = g.snapshot()
        assertFalse(g.restore(before.copy(width = Float.NaN)))
        assertFalse(g.restore(before.copy(leftScore = -1)))
        assertFalse(g.restore(before.copy(left = before.left.copy(vx = Float.NaN))))
        assertEquals(before, g.snapshot())
    }
    @Test fun restoredValuesAreClamped() {
        val g = game(); val s = g.snapshot()
        assertTrue(g.restore(s.copy(left = s.left.copy(x = -1000f, vx = 10_000f), leftCooldown = 100f)))
        assertValid(g); assertTrue(hypot(g.leftBall.vx, g.leftBall.vy) <= 600.001f)
        assertEquals(.15f, g.snapshot().leftCooldown, 0f)
    }
    @Test fun scoreNeverOverflows() {
        val g = game(); g.restore(g.snapshot().copy(leftScore = Int.MAX_VALUE))
        hitLeft(g); assertEquals(Int.MAX_VALUE, g.leftScore)
    }
    @Test fun randomizedLongRunRemainsFiniteAndInsideField() {
        val r = Random(20261015)
        val g = game()
        repeat(30_000) { i ->
            if (i % 111 == 0) g.resize(r.nextInt(200, 2560), r.nextInt(200, 1440))
            if (i % 17 == 0) g.hit(g.leftBall.x, g.leftBall.y)
            if (i % 19 == 0) g.hit(g.rightBall.x, g.rightBall.y)
            g.advance(r.nextFloat() * .04f)
            assertValid(g)
            assertTrue(g.leftScore >= 0 && g.rightScore >= 0)
        }
    }
}
