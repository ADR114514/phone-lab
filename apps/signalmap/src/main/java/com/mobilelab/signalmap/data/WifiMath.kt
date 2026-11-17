package com.mobilelab.signalmap.data

/**
 * Pure helpers that turn raw radio values into the values required by the data
 * dictionary. Kept free of Android imports so they can be unit tested on a
 * plain JVM.
 */
object WifiMath {

    /** "2.4G" / "5G" / "6G" as required by the survey_*.csv wifi_standard column. */
    fun bandOf(frequencyMhz: Int?): String {
        val f = frequencyMhz ?: return ""
        return when {
            f <= 0 -> ""
            f < 3000 -> "2.4G"
            f < 5925 -> "5G"
            else -> "6G"
        }
    }

    /** Wi-Fi channel inferred from the centre frequency; null when unknown. */
    fun channelOf(frequencyMhz: Int?): Int? {
        val f = frequencyMhz ?: return null
        return when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5000..5895 -> (f - 5000) / 5
            f in 5925..7125 -> (f - 5950) / 5
            else -> null
        }
    }

    /**
     * WifiManager.dhcpInfo.gateway stores the IPv4 address in network byte
     * order inside an int. This converts it to dotted quad form.
     */
    fun intToIpv4(raw: Int): String =
        (raw and 0xff).toString() + "." +
            ((raw shr 8) and 0xff) + "." +
            ((raw shr 16) and 0xff) + "." +
            ((raw shr 24) and 0xff)

    /** Removes the quotes WifiInfo adds around SSIDs and filters placeholder values. */
    fun sanitizeSsid(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val unquoted = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
        return if (unquoted.isEmpty() || unquoted.equals("<unknown ssid>", ignoreCase = true) ||
            unquoted.equals("0x", ignoreCase = true)
        ) {
            ""
        } else {
            unquoted
        }
    }

    /** Filters out the placeholder MAC Android returns when it cannot read the BSSID. */
    fun sanitizeBssid(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return ""
        if (value == "02:00:00:00:00:00" || value == "00:00:00:00:00:00") return ""
        return value
    }

    /** Sorted, de-duplicated channel list rendered as "1;6;11" (empty when no data). */
    fun neighborChannels(channels: Iterable<Int>): String =
        channels.distinct().sorted().joinToString(";")

    /** Parses a "host:port" setting; blank port falls back to the default. */
    fun parseHostPort(text: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "" to defaultPort
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0 || colon == trimmed.length - 1) return trimmed to defaultPort
        val host = trimmed.substring(0, colon).trim()
        val port = trimmed.substring(colon + 1).trim().toIntOrNull() ?: defaultPort
        return host to port.coerceIn(1, 65535)
    }
}
