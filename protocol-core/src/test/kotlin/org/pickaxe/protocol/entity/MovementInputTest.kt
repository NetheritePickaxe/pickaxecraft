package org.pickaxe.protocol.entity

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovementInputTest {

    @Test
    fun `默认状态七个按键位都不置起`() {
        val input = MovementInput()
        assertFalse(input.forward || input.backward || input.left || input.right)
        assertFalse(input.jump || input.sneaking || input.sprinting)
    }

    @Test
    fun `互斥方向不允许同时按下`() {
        assertFailsWith<IllegalArgumentException> { MovementInput(forward = true, backward = true) }
        assertFailsWith<IllegalArgumentException> { MovementInput(left = true, right = true) }
    }

    @Test
    fun `copy 保留其它按键位`() {
        val input = MovementInput(forward = true, sneaking = true)
        val sprinting = input.copy(sprinting = true, sneaking = false)
        assertTrue(sprinting.forward, "改冲刺不应丢掉前进键")
        assertTrue(sprinting.sprinting)
        assertFalse(sprinting.sneaking)
    }
}
