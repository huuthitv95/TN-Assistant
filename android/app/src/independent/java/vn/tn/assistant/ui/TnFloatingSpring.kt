package vn.tn.assistant.ui

import kotlin.math.abs

/** Small native RK4 spring; no reference-app runtime or native library is loaded. */
internal class TnFloatingSpring(start: Double = 0.0, tension: Double = 40.0, friction: Double = 7.0) {
    var value = start; private set
    var target = start
    private var velocity = 0.0
    private var stiffness = 0.0
    private var damping = 0.0
    private var previousPosition = 0.0
    private var previousVelocity = 0.0
    private var temporaryPosition = start
    private var temporaryVelocity = 0.0
    private var accumulator = 0.0
    private var wasAtRest = true

    init { configure(tension, friction) }
    fun configure(tension: Double, friction: Double) {
        stiffness = if (tension == 0.0) 0.0 else (tension - 30) * 3.62 + 194
        damping = if (friction == 0.0) 0.0 else 25 + (friction - 8) * 3
    }
    fun snap(position: Double) { value = position; target = position; temporaryPosition = position; velocity = 0.0 }
    val isAtRest get() = abs(velocity) <= .005 && (abs(target - value) <= .005 || stiffness == 0.0)
    val needsStep get() = !(isAtRest && wasAtRest)
    fun advance(seconds: Double): Boolean {
        if (isAtRest && wasAtRest) return false
        accumulator += seconds.coerceIn(0.0, .064)
        var position = value
        var speed = velocity
        var intermediatePosition = temporaryPosition
        var intermediateSpeed = temporaryVelocity
        while (accumulator >= .001) {
            accumulator -= .001
            if (accumulator < .001) { previousPosition = position; previousVelocity = speed }
            val aAcceleration = (target - intermediatePosition) * stiffness - damping * speed
            val bSpeed = aAcceleration * .001 * .5 + speed
            val bAcceleration = (target - (speed * .001 * .5 + position)) * stiffness - damping * bSpeed
            val cSpeed = bAcceleration * .001 * .5 + speed
            val cAcceleration = (target - (bSpeed * .001 * .5 + position)) * stiffness - damping * cSpeed
            val dPosition = cSpeed * .001 + position
            val dSpeed = cAcceleration * .001 + speed
            val acceleration = ((bAcceleration + cAcceleration) * 2 + aAcceleration + ((target - dPosition) * stiffness - damping * dSpeed)) / 6
            position += ((bSpeed + cSpeed) * 2 + speed + dSpeed) / 6 * .001
            speed += acceleration * .001
            intermediatePosition = dPosition; intermediateSpeed = dSpeed
        }
        temporaryPosition = intermediatePosition; temporaryVelocity = intermediateSpeed
        value = position; velocity = speed
        if (accumulator > 0) {
            val alpha = accumulator / .001
            value = previousPosition * (1 - alpha) + position * alpha
            velocity = previousVelocity * (1 - alpha) + speed * alpha
        }
        val resting = isAtRest
        if (resting) { if (stiffness > 0) value = target else target = value; velocity = 0.0 }
        wasAtRest = resting
        return !resting
    }
}
