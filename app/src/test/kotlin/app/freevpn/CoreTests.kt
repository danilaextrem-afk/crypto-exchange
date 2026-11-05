package app.freevpn

import app.freevpn.core.Backoff
import app.freevpn.core.HealthTracker
import app.freevpn.core.RoutingMode
import app.freevpn.core.RoutingPlanner
import app.freevpn.core.ServerProfile
import app.freevpn.core.ServerScorer
import app.freevpn.core.ServerStats
import app.freevpn.core.SpeedMeter
import app.freevpn.core.SplitPlan
import app.freevpn.core.SubscriptionParser
import app.freevpn.core.Verdict
import app.freevpn.vpn.TunnelConfigFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val CONF = """
# Name: Test DE
# Country: de
[Interface]
PrivateKey = YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.2/32

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 10.8.0.0/24
Endpoint = 203.0.113.7:51820
"""

class RoutingPlannerTest {
    private val installed = setOf("ru.sberbankmobile", "ru.rostel", "com.example.foreign", "app.freevpn")

    @Test fun allMode_hasNoRules() {
        assertEquals(SplitPlan.NONE, RoutingPlanner.plan(RoutingMode.ALL, installed, emptySet(), emptySet(), "app.freevpn"))
    }

    @Test fun ruDirect_excludesOnlyInstalledRussianApps() {
        val p = RoutingPlanner.plan(RoutingMode.RU_DIRECT, installed, emptySet(), emptySet(), "app.freevpn")
        assertEquals(setOf("ru.sberbankmobile", "ru.rostel"), p.exclude)
        assertTrue(p.include.isEmpty())
    }

    @Test fun ruDirect_neverExcludesOwnPackage() {
        val p = RoutingPlanner.plan(RoutingMode.RU_DIRECT, installed, emptySet(), setOf("app.freevpn"), "app.freevpn")
        assertFalse("app.freevpn" in p.exclude)
    }

    @Test fun ruDirect_addsUserDirectApps() {
        val p = RoutingPlanner.plan(RoutingMode.RU_DIRECT, installed, emptySet(), setOf("com.example.foreign", "not.installed"), "app.freevpn")
        assertTrue("com.example.foreign" in p.exclude)
        assertFalse("not.installed" in p.exclude)
    }

    @Test fun manual_includesSelectedAndOwn_neverBoth() {
        val p = RoutingPlanner.plan(RoutingMode.MANUAL, installed, setOf("com.example.foreign", "gone.app"), emptySet(), "app.freevpn")
        assertEquals(setOf("com.example.foreign", "app.freevpn"), p.include)
        assertTrue(p.exclude.isEmpty())
    }

    @Test fun manual_emptySelection_stillTunnelsOwnApp() {
        val p = RoutingPlanner.plan(RoutingMode.MANUAL, installed, emptySet(), emptySet(), "app.freevpn")
        assertEquals(setOf("app.freevpn"), p.include)
    }
}

class ScorerTest {
    private fun srv(id: String, load: Int? = null) = ServerProfile(id, id, "DE", CONF, load)
    private val now = 1_000_000_000L

    @Test fun lowerLatencyWins() {
        val stats = mapOf(
            "a" to ServerStats(latencyMs = 40.0, lastTestedEpoch = now),
            "b" to ServerStats(latencyMs = 120.0, lastTestedEpoch = now)
        )
        assertEquals("a", ServerScorer.order(listOf(srv("b"), srv("a")), stats, now).first().id)
    }

    @Test fun failuresAndLoadPushServerDown() {
        val stats = mapOf(
            "a" to ServerStats(latencyMs = 30.0, consecutiveFailures = 2, lastTestedEpoch = now),
            "b" to ServerStats(latencyMs = 90.0, lastTestedEpoch = now)
        )
        assertEquals("b", ServerScorer.order(listOf(srv("a"), srv("b")), stats, now).first().id)
        val loaded = ServerScorer.score(srv("x", 100), ServerStats(latencyMs = 50.0, lastTestedEpoch = now), now)
        val idle = ServerScorer.score(srv("x", 0), ServerStats(latencyMs = 50.0, lastTestedEpoch = now), now)
        assertTrue(loaded > idle)
    }

    @Test fun fasterDownloadImprovesScore() {
        val slow = ServerScorer.score(srv("a"), ServerStats(latencyMs = 50.0, downMbps = 5.0, lastTestedEpoch = now), now)
        val fast = ServerScorer.score(srv("a"), ServerStats(latencyMs = 50.0, downMbps = 80.0, lastTestedEpoch = now), now)
        assertTrue(fast < slow)
    }

    @Test fun preferredFirst_avoidedLast() {
        val stats = mapOf("a" to ServerStats(latencyMs = 10.0, lastTestedEpoch = now), "b" to ServerStats(latencyMs = 20.0, lastTestedEpoch = now), "c" to ServerStats(latencyMs = 30.0, lastTestedEpoch = now))
        val order = ServerScorer.order(listOf(srv("a"), srv("b"), srv("c")), stats, now, preferredId = "c", avoid = setOf("a")).map { it.id }
        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30), (1..7).map { Backoff.seconds(it) })
    }
}

class HealthAndSpeedTest {
    @Test fun healthTrackerEscalatesAndRecovers() {
        val h = HealthTracker(3)
        assertEquals(Verdict.DEGRADED, h.record(false))
        assertEquals(Verdict.DEGRADED, h.record(false))
        assertEquals(Verdict.DEAD, h.record(false))
        assertEquals(Verdict.HEALTHY, h.record(true))
        assertEquals(0, h.consecutiveFailures)
    }

    @Test fun speedMeterComputesRates() {
        val m = SpeedMeter()
        assertEquals(0L to 0L, m.update(0, 0, 1000))
        assertEquals(1_000_000L to 500_000L, m.update(1_000_000, 500_000, 2000))
    }

    @Test fun speedMeterIgnoresCounterReset() {
        val m = SpeedMeter()
        m.update(5_000, 5_000, 1000)
        assertEquals(0L to 0L, m.update(100, 100, 2000))
    }
}

class SubscriptionParserTest {
    @Test fun parsesSingleConfigWithComments() {
        val s = SubscriptionParser.parse(CONF).single()
        assertEquals("Test DE", s.name)
        assertEquals("DE", s.country)
        assertEquals("203.0.113.7", s.endpointHost)
        assertEquals(12, s.id.length)
    }

    @Test fun sameConfigGivesSameId() {
        assertEquals(SubscriptionParser.parse(CONF).single().id, SubscriptionParser.parse("  $CONF  ").single().id)
    }

    @Test fun parsesJsonSubscription() {
        val json = org.json.JSONObject().put("servers", org.json.JSONArray().put(
            org.json.JSONObject().put("name", "Frankfurt").put("country", "de").put("load", 12).put("config", CONF)
        )).toString()
        val s = SubscriptionParser.parse(json, "sub").single()
        assertEquals("Frankfurt", s.name)
        assertEquals(12, s.load)
        assertEquals("sub", s.source)
    }

    @Test fun rejectsGarbageAndIncompleteConfigs() {
        for (bad in listOf("", "hello", "[Interface]\nPrivateKey = x", "{\"servers\":[]}")) {
            try {
                SubscriptionParser.parse(bad)
                fail("Должно быть отклонено: $bad")
            } catch (e: Exception) { /* ожидаемо */ }
        }
    }
}

class TunnelConfigFactoryTest {
    @Test fun forcesFullTunnelKeepaliveDnsAndMtu() {
        val cfg = TunnelConfigFactory.build(CONF, SplitPlan.NONE)
        val peer = cfg.peers.single()
        val allowed = peer.allowedIps.map { it.toString() }
        assertTrue("0.0.0.0/0" in allowed)
        assertTrue("::/0" in allowed)
        assertEquals(25, peer.persistentKeepalive.get())
        assertEquals(TunnelConfigFactory.DEFAULT_MTU, cfg.`interface`.mtu.get())
        assertFalse(cfg.`interface`.dnsServers.isEmpty())
    }

    @Test fun appliesSplitTunnelingLists() {
        val ex = TunnelConfigFactory.build(CONF, SplitPlan(emptySet(), setOf("ru.rostel")))
        assertEquals(listOf("ru.rostel"), ex.`interface`.excludedApplications.toList())
        val inc = TunnelConfigFactory.build(CONF, SplitPlan(setOf("app.freevpn"), emptySet()))
        assertEquals(listOf("app.freevpn"), inc.`interface`.includedApplications.toList())
    }
}
