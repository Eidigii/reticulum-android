package tech.torlando.rns.rnode.viewmodel

import org.json.JSONObject

/**
 * LoRa radio parameters plus IFAC (network name, passphrase and size).
 * The interface name and the device (Bluetooth, USB, TCP) stay out of the file.
 *
 * JSON keys use the same names as an rnsd config, so a snippet such as
 * `network_name = mynet` imports the same way as an exported file.
 */
internal object RadioSettingsTransfer {
    const val FORMAT = "rnode-radio-settings"

    private val KEYS =
        listOf(
            "frequency",
            "bandwidth",
            "spreading_factor",
            "coding_rate",
            "tx_power",
            "st_alock",
            "lt_alock",
            "network_name",
            "passphrase",
            "ifac_size",
        )

    /** Present even when blank, so an import can clear IFAC that is no longer set. */
    private val IFAC_KEYS = setOf("network_name", "passphrase", "ifac_size")

    private val ALIASES =
        mapOf(
            "frequency" to "frequency",
            "bandwidth" to "bandwidth",
            "spreadingfactor" to "spreading_factor",
            "spreading_factor" to "spreading_factor",
            "codingrate" to "coding_rate",
            "coding_rate" to "coding_rate",
            "txpower" to "tx_power",
            "tx_power" to "tx_power",
            "airtime_limit_short" to "st_alock",
            "airtime_limit_long" to "lt_alock",
            "st_alock" to "st_alock",
            "lt_alock" to "lt_alock",
            "network_name" to "network_name",
            "networkname" to "network_name",
            "passphrase" to "passphrase",
            "ifac_size" to "ifac_size",
            "ifacsize" to "ifac_size",
        )

    fun toJson(
        frequency: String,
        bandwidth: String,
        spreadingFactor: String,
        codingRate: String,
        txPower: String,
        stAlock: String,
        ltAlock: String,
        networkName: String,
        passphrase: String,
        ifacSize: String,
    ): String {
        val obj =
            JSONObject()
                .put("format", FORMAT)
                .put("version", 1)
                .put("frequency", frequency.toLongOrNull() ?: 0L)
                .put("bandwidth", bandwidth.toLongOrNull() ?: 0L)
                .put("spreading_factor", spreadingFactor.toIntOrNull() ?: 0)
                .put("coding_rate", codingRate.toIntOrNull() ?: 0)
                .put("tx_power", txPower.toIntOrNull() ?: 0)
        stAlock.toDoubleOrNull()?.let { obj.put("st_alock", it) }
        ltAlock.toDoubleOrNull()?.let { obj.put("lt_alock", it) }
        obj.put("network_name", networkName)
        obj.put("passphrase", passphrase)
        // Empty size means "RNS default". Write null so import clears the field
        // instead of turning it into 0, which the wizard rejects.
        if (ifacSize.isBlank()) obj.put("ifac_size", JSONObject.NULL)
        else obj.put("ifac_size", ifacSize.toIntOrNull() ?: ifacSize)
        return obj.toString(2)
    }

    /**
     * Radio and IFAC values from an export file, or from an rnsd-style config
     * snippet. Null when the text is JSON with a different `format`.
     * A key is absent when the source did not mention it.
     * An IFAC value may be empty, which means "clear this field".
     */
    fun parse(text: String): Map<String, String>? {
        if (text.startsWith("{")) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (obj.has("format") && obj.optString("format") != FORMAT) return null
            return KEYS.mapNotNull { key ->
                if (!obj.has(key)) return@mapNotNull null
                val value = if (obj.isNull(key)) "" else formatNumber(obj.get(key))
                key to value
            }.toMap()
        }
        return text.lineSequence()
            .map { stripConfigComment(it).trim() }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = ALIASES[line.substring(0, idx).trim().lowercase()] ?: return@mapNotNull null
                val value = unquote(line.substring(idx + 1).trim())
                if (value.isEmpty() && key !in IFAC_KEYS) null else key to value
            }
            .toMap()
    }

    private fun formatNumber(value: Any): String =
        when (value) {
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            else -> value.toString()
        }

    /** `#` starts a comment, except inside quotes, so a passphrase can contain one. */
    private fun stripConfigComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble) return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val quote = value.first()
            if ((quote == '"' || quote == '\'') && value.last() == quote) {
                return value.substring(1, value.length - 1).trim()
            }
        }
        return value
    }
}
