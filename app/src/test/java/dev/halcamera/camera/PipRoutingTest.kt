package dev.halcamera.camera

import org.junit.Assert.*
import org.junit.Test

class PipRoutingTest {
    @Test fun physicalSourceStaysOnItsParentDevice() {
        assertEquals(listOf(PipInputRoute("0"),PipInputRoute("0","5")), PipRouting.inputs("0",listOf("5"),null))
    }
    @Test fun frontServiceSourceUsesItsOwnDeviceWithoutPhysicalBinding() {
        assertEquals(listOf(PipInputRoute("0"),PipInputRoute("1")), PipRouting.inputs("0",emptyList(),"1"))
    }
    @Test fun anExistingMultiDeviceIsOpenedOnlyOnce() {
        val routes = PipRouting.inputs("0",emptyList(),"1") + PipRouting.inputs("1",emptyList(),null)
        assertEquals(listOf("0","1"),routes.map { it.deviceId }.distinct())
        assertEquals(2,routes.count { it.deviceId == "1" })
    }
    @Test fun sourceKindDistinguishesIdenticalPhysicalAndServiceIds() {
        assertNotEquals(PipSource("2",true,"Physical 2").key,PipSource("2",false,"Rear 2").key)
    }
    @Test(expected = IllegalArgumentException::class) fun multiplePhysicalSourcesAreRejected() {
        PipRouting.inputs("0",listOf("2","5"),null)
    }
    @Test(expected = IllegalArgumentException::class) fun physicalAndServiceCannotBothBeSelected() {
        PipRouting.inputs("0",listOf("2"),"1")
    }
    @Test(expected = IllegalArgumentException::class) fun mainServiceCannotBeItsOwnPip() {
        PipRouting.inputs("0",emptyList(),"0")
    }
}
