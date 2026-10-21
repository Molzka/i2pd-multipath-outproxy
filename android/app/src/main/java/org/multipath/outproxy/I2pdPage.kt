package org.multipath.outproxy

object I2pdPage {
    private fun num(html: String, label: String): Int? =
        Regex("$label:\\s*</b>\\s*(\\d+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.toIntOrNull()

    fun routers(html: String) = num(html, "Routers")
    fun clientTunnels(html: String) = num(html, "Client Tunnels")
    fun successRate(html: String) = num(html, "Tunnel creation success rate")
}
