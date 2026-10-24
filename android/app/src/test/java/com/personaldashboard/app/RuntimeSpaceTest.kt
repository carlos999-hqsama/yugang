package com.personaldashboard.app

import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeSpaceTest {
    @Test fun `zero available space and invalid unpacked size stop before extraction`() {
        assertThrows(IllegalStateException::class.java) { checkRuntimeSpace(1024, 0) }
        assertThrows(IllegalArgumentException::class.java) { checkRuntimeSpace(0, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { checkRuntimeSpace(-1, Long.MAX_VALUE) }
    }
    @Test fun `physical capacity includes the existing reserve without assuming reclaimable cache`() {
        val required = 1024L + (64L shl 20)
        assertThrows(IllegalStateException::class.java) { checkRuntimeSpace(1024, required - 1) }
        checkRuntimeSpace(1024, required)
        checkRuntimeSpace(1024, required + 1)
        assertThrows(ArithmeticException::class.java) { checkRuntimeSpace(Long.MAX_VALUE, Long.MAX_VALUE) }
    }
}
