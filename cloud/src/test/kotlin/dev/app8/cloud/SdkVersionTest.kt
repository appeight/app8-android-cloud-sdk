package dev.app8.cloud

import dev.app8.cloud.internal.SDKVersion
import dev.app8.engine.core.EngineVersion
import org.junit.Test
import kotlin.test.assertEquals

class SdkVersionTest {
    /** One release train: App8Cloud x.y.z bundles engine x.y.z. */
    @Test fun sdkVersionEqualsEngineVersion() {
        assertEquals(EngineVersion.CURRENT, SDKVersion.CURRENT)
        assertEquals(SDKVersion.CURRENT, App8Cloud.VERSION)
    }
}
