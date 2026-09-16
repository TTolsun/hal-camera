package dev.halcamera.ctsvendor

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A stand-in for a vendored CTS class: Parameterized like CameraParameterizedTestCase, with one method per
 * outcome. Gradle discovers and runs it directly too, so every method is harmless until [VendoredRunTest]
 * arms it.
 */
@RunWith(Parameterized::class)
class DriverFixture {
    @JvmField @Parameterized.Parameter(0) var adoptShellPerm: Boolean = false

    @Test fun testPasses() {}

    @Test fun testFails() { if (armed) fail("Camera 0: fixture failure") }

    @Test fun testSkips() { assumeTrue(!armed) }

    @Test fun testWaits() { if (armed) release.await(5, TimeUnit.SECONDS) }

    companion object {
        @JvmStatic @Parameterized.Parameters fun data(): Iterable<Any> = listOf(false)

        @Volatile var armed = false
        @Volatile var release = CountDownLatch(1)
    }
}
