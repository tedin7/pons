package io.github.pons

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun laterVersionsAreNewer() {
        assertTrue(UpdateCheck.newer("0.1.1", "0.1.0"))
        assertTrue(UpdateCheck.newer("0.2", "0.1.9"))
        assertTrue(UpdateCheck.newer("1.0.0", "0.10.0"))
    }

    @Test fun sameOrOlderVersionsAreNot() {
        assertFalse(UpdateCheck.newer("0.1.0", "0.1.0"))
        assertFalse(UpdateCheck.newer("0.1.0", "0.1.0-dev"))
        assertFalse(UpdateCheck.newer("0.0.9", "0.1.0"))
        assertFalse(UpdateCheck.newer("garbage", "0.1.0"))
    }
}
