package io.github.pons

import io.github.pons.linux.NetworkConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkConfigTest {
    @Test fun networkServersComeFirstThenPublicOnes() {
        assertEquals("nameserver 10.11.88.78\nnameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:1 attempts:2\n",
            NetworkConfig.contents(listOf("10.11.88.78")))
    }

    @Test fun noNetworkServersStillResolves() {
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:1 attempts:2\n", NetworkConfig.contents(emptyList()))
    }

    @Test fun atMostThreeServersWithoutDuplicates() {
        assertEquals("nameserver 8.8.8.8\nnameserver 100.100.100.100\nnameserver 192.168.1.1\noptions timeout:1 attempts:2\n",
            NetworkConfig.contents(listOf("8.8.8.8", "100.100.100.100", "192.168.1.1")))
    }
}
