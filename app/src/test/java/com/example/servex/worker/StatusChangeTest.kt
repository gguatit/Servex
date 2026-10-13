package com.example.servex.worker

import com.example.servex.domain.ServerStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusChangeTest {
    @Test fun notifyOnWorsening() = assertTrue(shouldNotify(ServerStatus.NORMAL, ServerStatus.WARNING))

    @Test fun noNotifyOnSame() = assertFalse(shouldNotify(ServerStatus.WARNING, ServerStatus.WARNING))

    @Test fun notifyOnRecovery() = assertTrue(shouldNotify(ServerStatus.ERROR, ServerStatus.NORMAL))

    @Test fun firstSeenHealthyIsSilent() = assertFalse(shouldNotify(null, ServerStatus.NORMAL))

    @Test fun firstSeenBrokenNotifies() = assertTrue(shouldNotify(null, ServerStatus.ERROR))
}
