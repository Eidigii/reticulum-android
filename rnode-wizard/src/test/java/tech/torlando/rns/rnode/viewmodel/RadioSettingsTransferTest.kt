package tech.torlando.rns.rnode.viewmodel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSettingsTransferTest {

    @Test
    fun `export includes radio parameters and all IFAC fields`() {
        val json =
            RadioSettingsTransfer.toJson(
                frequency = "867200000",
                bandwidth = "125000",
                spreadingFactor = "8",
                codingRate = "5",
                txPower = "7",
                stAlock = "1.5",
                ltAlock = "10",
                networkName = "mesh net",
                passphrase = "s3cret = #1",
                ifacSize = "64",
            )
        val parsed = RadioSettingsTransfer.parse(json)!!

        assertEquals("867200000", parsed["frequency"])
        assertEquals("125000", parsed["bandwidth"])
        assertEquals("8", parsed["spreading_factor"])
        assertEquals("5", parsed["coding_rate"])
        assertEquals("7", parsed["tx_power"])
        assertEquals("1.5", parsed["st_alock"])
        assertEquals("10", parsed["lt_alock"])
        assertEquals("mesh net", parsed["network_name"])
        assertEquals("s3cret = #1", parsed["passphrase"])
        assertEquals("64", parsed["ifac_size"])
    }

    @Test
    fun `empty IFAC exports as blank so import can clear it`() {
        val json =
            RadioSettingsTransfer.toJson(
                frequency = "915000000",
                bandwidth = "125000",
                spreadingFactor = "8",
                codingRate = "5",
                txPower = "7",
                stAlock = "",
                ltAlock = "",
                networkName = "",
                passphrase = "",
                ifacSize = "",
            )
        val obj = JSONObject(json)
        assertEquals("", obj.getString("network_name"))
        assertEquals("", obj.getString("passphrase"))
        assertTrue(obj.isNull("ifac_size"))

        val parsed = RadioSettingsTransfer.parse(json)!!
        assertEquals("", parsed["network_name"])
        assertEquals("", parsed["passphrase"])
        assertEquals("", parsed["ifac_size"])
        assertFalse(parsed.containsKey("st_alock"))
        assertFalse(parsed.containsKey("lt_alock"))
    }

    @Test
    fun `older export without IFAC keys leaves those fields absent`() {
        val json =
            """
            {
              "format": "rnode-radio-settings",
              "version": 1,
              "frequency": 867200000,
              "bandwidth": 125000,
              "spreading_factor": 8,
              "coding_rate": 5,
              "tx_power": 7
            }
            """.trimIndent()
        val parsed = RadioSettingsTransfer.parse(json)!!
        assertEquals("867200000", parsed["frequency"])
        assertFalse(parsed.containsKey("network_name"))
        assertFalse(parsed.containsKey("passphrase"))
        assertFalse(parsed.containsKey("ifac_size"))
    }

    @Test
    fun `rnsd snippet imports IFAC including a quoted passphrase`() {
        val snippet =
            """
            frequency = 867200000
            spreadingfactor = 8
            txpower = 7
            network_name = "mesh net"
            passphrase = "secret #1" # comment
            ifac_size = 16
            # passphrase = ignored
            """.trimIndent()
        val parsed = RadioSettingsTransfer.parse(snippet)!!
        assertEquals("867200000", parsed["frequency"])
        assertEquals("8", parsed["spreading_factor"])
        assertEquals("7", parsed["tx_power"])
        assertEquals("mesh net", parsed["network_name"])
        assertEquals("secret #1", parsed["passphrase"])
        assertEquals("16", parsed["ifac_size"])
    }

    @Test
    fun `blank IFAC lines in a snippet clear those fields`() {
        val parsed =
            RadioSettingsTransfer.parse(
                """
                networkname = 
                passphrase =
                ifacsize =
                """.trimIndent(),
            )!!
        assertEquals("", parsed["network_name"])
        assertEquals("", parsed["passphrase"])
        assertEquals("", parsed["ifac_size"])
    }

    @Test
    fun `wrong format is rejected`() {
        assertNull(RadioSettingsTransfer.parse("""{"format":"other","frequency":1}"""))
    }
}
