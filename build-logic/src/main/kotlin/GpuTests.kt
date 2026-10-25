import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Held by the test runs that start compositors on the GPU and wait for them with time
 * limits: one module at a time (several starting their GPU at once can take longer than
 * those limits, and the tests fail for no fault of their own).
 */
abstract class GpuTests : BuildService<BuildServiceParameters.None>
