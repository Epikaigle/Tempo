package me.avinas.tempo.data.importexport

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportExportOperationGateTest {

    @Test
    fun `only one import or export owns the gate`() {
        val gate = ImportExportOperationGate()
        val first = gate.tryAcquire()

        assertNotNull(first)
        assertNull(gate.tryAcquire())

        first!!.release()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `releasing the same lease twice does not unlock a newer owner`() {
        val gate = ImportExportOperationGate()
        val first = gate.tryAcquire()!!
        first.release()

        val second = gate.tryAcquire()
        assertNotNull(second)

        first.release()
        assertNull(gate.tryAcquire())
    }
}
