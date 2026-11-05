package app.freevpn.vpn

import app.freevpn.core.SplitPlan
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress

/**
 * Превращает конфиг сервера в итоговый конфиг туннеля:
 *  - всегда полный туннель (0.0.0.0/0 и ::/0) — это защита от утечек IPv4/IPv6;
 *  - DNS берётся из конфига, а если его нет — Cloudflare/Quad9 через туннель;
 *  - PersistentKeepalive принудительно 25 с (мобильные NAT);
 *  - Split Tunneling накладывается списками include/exclude.
 */
object TunnelConfigFactory {
    const val DEFAULT_MTU = 1380
    private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")

    fun build(configText: String, plan: SplitPlan): Config {
        val base = Config.parse(BufferedReader(StringReader(configText)))
        val bi = base.`interface`

        val ib = Interface.Builder()
        ib.setKeyPair(bi.keyPair)
        ib.addAddresses(bi.addresses)
        val dns = if (bi.dnsServers.isEmpty()) FALLBACK_DNS.map { InetAddress.getByName(it) } else bi.dnsServers
        ib.addDnsServers(dns)
        ib.setMtu(bi.mtu.orElse(DEFAULT_MTU))
        when {
            plan.include.isNotEmpty() -> ib.includeApplications(plan.include)
            plan.exclude.isNotEmpty() -> ib.excludeApplications(plan.exclude)
        }

        val cb = Config.Builder().setInterface(ib.build())
        for (p in base.peers) {
            val pb = Peer.Builder().setPublicKey(p.publicKey)
            p.endpoint.ifPresent { pb.setEndpoint(it) }
            p.preSharedKey.ifPresent { pb.setPreSharedKey(it) }
            pb.addAllowedIps(p.allowedIps)
            pb.addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            pb.addAllowedIp(InetNetwork.parse("::/0"))
            val ka = p.persistentKeepalive.orElse(0)
            pb.setPersistentKeepalive(if (ka in 1..120) ka else 25)
            cb.addPeer(pb.build())
        }
        return cb.build()
    }
}
