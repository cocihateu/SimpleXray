package com.simplexray.re.common

import com.simplexray.re.common.config.ConfigSanitizer
import com.simplexray.re.prefs.FakeContext
import com.simplexray.re.prefs.LogLevel
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.prefs.TunnelMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigUtilsTest {

    private val prefs = Preferences(FakeContext()).apply {
        tunnelMode = TunnelMode.HevSocks5Tunnel
        logLevel = LogLevel.Auto
        accessLog = true
        bypassLan = false
    }

    @Test
    fun testDesktopTunInboundRemoval() {
        val rawConfig = """
        {
          "inbounds": [
            {
              "tag": "tun-in",
              "protocol": "tun",
              "settings": { "name": "utun10" }
            },
            {
              "tag": "socks-in",
              "port": 10808,
              "protocol": "socks"
            }
          ]
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val inbounds = json.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        val inbound = inbounds.getJSONObject(0)
        assertEquals("socks-in", inbound.getString("tag"))
    }

    @Test
    fun testGlobalListenAddressConversion() {
        val rawConfig = """
        {
          "inbounds": [
            {
              "tag": "socks-in",
              "port": 10808,
              "listen": "::",
              "protocol": "socks"
            },
            {
              "tag": "http-in",
              "port": 10809,
              "listen": "0.0.0.0",
              "protocol": "http"
            }
          ]
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val inbounds = json.getJSONArray("inbounds")

        assertEquals("127.0.0.1", inbounds.getJSONObject(0).getString("listen"))
        assertEquals("127.0.0.1", inbounds.getJSONObject(1).getString("listen"))
    }

    @Test
    fun testLogLevelHandlingInAutoMode() {
        val rawConfig = """
        {
          "log": {
            "loglevel": "info",
            "access": "/var/log/access.log",
            "error": "/var/log/error.log"
          }
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val log = json.getJSONObject("log")

        assertFalse(log.has("access"))
        assertFalse(log.has("error"))
        assertEquals("info", log.getString("loglevel"))
    }

    @Test
    fun testCreateDefaultSniffingObject() {
        val sniffing = ConfigSanitizer.createDefaultSniffingObject()
        assertTrue(sniffing.getBoolean("enabled"))
        assertFalse(sniffing.getBoolean("metadataOnly"))
        assertFalse(sniffing.getBoolean("routeOnly"))

        val destOverride = sniffing.getJSONArray("destOverride")
        val overrides = (0 until destOverride.length()).map { destOverride.getString(it) }.toSet()
        assertTrue(overrides.contains("http"))
        assertTrue(overrides.contains("tls"))
        assertTrue(overrides.contains("quic"))
        assertTrue(overrides.contains("fakedns"))
    }

    @Test
    fun testRulePruningRetainsValidFields() {
        val rawConfig = """
        {
          "routing": {
            "rules": [
              {
                "sourceIP": ["192.168.1.100"],
                "outboundTag": "direct"
              },
              {
                "sourcePort": "1000-2000",
                "outboundTag": "direct"
              },
              {
                "localIP": ["10.0.0.1"],
                "outboundTag": "direct"
              }
            ]
          }
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3, rules.length())
    }

    @Test
    fun testRuleMigrationOfLegacyGeositeAndGeoip() {
        val rawConfig = """
        {
          "routing": {
            "rules": [
              {
                "geosite": ["google", "geosite:cn"],
                "outboundTag": "proxy"
              },
              {
                "geoip": ["cn"],
                "outboundTag": "direct"
              }
            ]
          }
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        assertEquals(2, rules.length())

        val rule0 = rules.getJSONObject(0)
        assertFalse(rule0.has("geosite"))
        assertTrue(rule0.has("domain"))
        val domainArr = rule0.getJSONArray("domain")
        assertEquals(2, domainArr.length())
        assertEquals("geosite:google", domainArr.getString(0))
        assertEquals("geosite:cn", domainArr.getString(1))

        val rule1 = rules.getJSONObject(1)
        assertFalse(rule1.has("geoip"))
        assertTrue(rule1.has("ip"))
        val ipArr = rule1.getJSONArray("ip")
        assertEquals(1, ipArr.length())
        assertEquals("geoip:cn", ipArr.getString(0))
    }

    @Test
    fun testRulePruningRemovesEmptyOrInvalidRules() {
        val rawConfig = """
        {
          "routing": {
            "rules": [
              {
                "outboundTag": "direct"
              },
              {
                "unknownField": ["something"],
                "outboundTag": "direct"
              },
              {
                "geosite": [],
                "outboundTag": "direct"
              }
            ]
          }
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val rules = json.getJSONObject("routing").getJSONArray("rules")
        assertEquals(0, rules.length())
    }

    @Test
    fun testMultipleSocksInboundsPreservesSecondaryPort() {
        val rawConfig = """
        {
          "inbounds": [
            {
              "tag": "socks-in",
              "port": 1080,
              "protocol": "socks"
            },
            {
              "tag": "socks-secondary",
              "port": 10809,
              "protocol": "socks",
              "listen": "0.0.0.0"
            }
          ]
        }
        """.trimIndent()

        val sanitized = ConfigUtils.sanitizeConfig(rawConfig, prefs)
        val json = JSONObject(sanitized)
        val inbounds = json.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())

        val primary = inbounds.getJSONObject(0)
        assertEquals("socks-in", primary.getString("tag"))
        assertEquals(10808, primary.getInt("port"))
        assertEquals("127.0.0.1", primary.getString("listen"))

        val secondary = inbounds.getJSONObject(1)
        assertEquals("socks-secondary", secondary.getString("tag"))
        assertEquals(10809, secondary.getInt("port"))
        assertEquals("127.0.0.1", secondary.getString("listen"))
    }

    @Test
    fun testOutboundSocketTimeoutsInjectedWithRespectForUserValues() {
        val rawConfig = """
        {
          "outbounds": [
            {
              "tag": "vless-node",
              "protocol": "vless",
              "streamSettings": {
                "network": "tcp",
                "sockopt": {
                  "tcpKeepAliveIdle": 30
                }
              }
            },
            {
              "tag": "trojan-node",
              "protocol": "trojan"
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            },
            {
              "tag": "wg-node",
              "protocol": "wireguard"
            }
          ]
        }
        """.trimIndent()

        val json = JSONObject(ConfigUtils.sanitizeConfig(rawConfig, prefs))
        val outbounds = json.getJSONArray("outbounds")

        val vlessSockopt = outbounds.getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals(30, vlessSockopt.getInt("tcpKeepAliveIdle"))
        assertEquals(3, vlessSockopt.getInt("tcpKeepAliveInterval"))
        assertEquals(15000, vlessSockopt.getInt("tcpUserTimeout"))
        assertEquals("3", vlessSockopt.getJSONArray("customSockopt").getJSONObject(0).getString("value"))

        val trojanSockopt = outbounds.getJSONObject(1)
            .getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals(15, trojanSockopt.getInt("tcpKeepAliveIdle"))

        assertFalse(outbounds.getJSONObject(2).has("streamSettings"))
        assertFalse(outbounds.getJSONObject(3).has("streamSettings"))
    }

    @Test
    fun testOutboundSocketTimeoutsRespectExistingKeepAliveCount() {
        val rawConfig = """
        {
          "outbounds": [
            {
              "tag": "vless-node",
              "protocol": "vless",
              "streamSettings": {
                "sockopt": {
                  "customSockopt": [
                    { "type": "int", "level": "6", "opt": "6", "value": "7" }
                  ]
                }
              }
            }
          ]
        }
        """.trimIndent()

        val json = JSONObject(ConfigUtils.sanitizeConfig(rawConfig, prefs))
        val custom = json.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("sockopt")
            .getJSONArray("customSockopt")
        assertEquals(1, custom.length())
        assertEquals("7", custom.getJSONObject(0).getString("value"))
    }

}
