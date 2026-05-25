package com.dkashev.cotbridge.cot

import org.meshtastic.proto.ATAKProtos
import java.time.Instant
import java.util.Locale

/**
 * Конвертер между CoT XML (формат ATAK) и Meshtastic [ATAKProtos.TAKPacket] (формат меш-сети).
 *
 * Поддерживает два сценария: PLI (типы `a-f-*`, `a-h-*`, `a-n-*`, `a-u-*`) и GeoChat (тип `b-t-f`).
 * Прочие CoT-типы (маркеры, draw, casevac и т.д.) пропускаются — bandwidth LoRa их не вытянет.
 */
object AtakPacketConverter {

    fun cotToTakPacket(xml: String): ATAKProtos.TAKPacket? {
        val type = CotXml.extractType(xml) ?: return null
        val uid = CotXml.extractUid(xml) ?: return null
        val callsign = extractCallsign(xml) ?: extractCallsignFromUid(uid)

        val builder = ATAKProtos.TAKPacket.newBuilder()
        builder.contact = ATAKProtos.Contact.newBuilder()
            .setCallsign(callsign.take(20))
            .setDeviceCallsign(callsign.take(20))
            .build()
        builder.group = ATAKProtos.Group.newBuilder()
            .setRole(extractRole(xml))
            .setTeam(extractTeam(xml))
            .build()

        when {
            isPliType(type) -> {
                val lat = extractPointAttr(xml, "lat") ?: return null
                val lon = extractPointAttr(xml, "lon") ?: return null
                val hae = extractPointAttr(xml, "hae") ?: 0.0
                val speed = extractElemAttr(xml, "track", "speed")?.toDoubleOrNull() ?: 0.0
                val course = extractElemAttr(xml, "track", "course")?.toDoubleOrNull() ?: 0.0
                builder.pli = ATAKProtos.PLI.newBuilder()
                    .setLatitudeI((lat * 1e7).toInt())
                    .setLongitudeI((lon * 1e7).toInt())
                    .setAltitude(hae.toInt())
                    .setSpeed(speed.toInt().coerceAtLeast(0))
                    .setCourse(course.toInt().coerceAtLeast(0).coerceAtMost(360))
                    .build()
            }
            type == "b-t-f" -> {
                val message = extractRemarksText(xml) ?: return null
                val to = extractElemAttr(xml, "__chat", "id")
                val toCallsign = extractElemAttr(xml, "__chat", "chatroom")
                val chat = ATAKProtos.GeoChat.newBuilder().setMessage(message.take(200))
                to?.let { chat.to = it }
                toCallsign?.let { chat.toCallsign = it }
                builder.chat = chat.build()
            }
            else -> return null
        }
        return builder.build()
    }

    fun takPacketToCot(packet: ATAKProtos.TAKPacket): String? {
        val callsign = packet.contact.callsign.ifEmpty { "Mesh" }
        val teamName = packet.group.team.name.replace("_", " ")
        val roleName = roleString(packet.group.role)
        val now = Instant.now().toString()
        val stale = Instant.now().plusSeconds(75).toString()

        return when (packet.payloadVariantCase) {
            ATAKProtos.TAKPacket.PayloadVariantCase.PLI -> {
                val pli = packet.pli
                val lat = pli.latitudeI / 1e7
                val lon = pli.longitudeI / 1e7
                val uid = "MESH-${callsign}"
                """<event version="2.0" uid="${esc(uid)}" type="a-f-G-U-C" time="$now" start="$now" stale="$stale" how="m-g">""" +
                    """<point lat="${"%.7f".format(Locale.US, lat)}" lon="${"%.7f".format(Locale.US, lon)}" hae="${pli.altitude}" ce="9999999" le="9999999"/>""" +
                    """<detail><contact callsign="${esc(callsign)}"/><__group name="${esc(teamName)}" role="${esc(roleName)}"/>""" +
                    """<track speed="${pli.speed}" course="${pli.course}"/></detail></event>"""
            }
            ATAKProtos.TAKPacket.PayloadVariantCase.CHAT -> {
                val chat = packet.chat
                val message = chat.message
                val toId = chat.to.ifEmpty { "All Chat Rooms" }
                val toCallsign = chat.toCallsign.ifEmpty { "All Chat Rooms" }
                val chatUid = "GeoChat.${callsign}.${System.nanoTime()}"
                """<event version="2.0" uid="${esc(chatUid)}" type="b-t-f" time="$now" start="$now" stale="$stale" how="h-g-i-g-o">""" +
                    """<point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>""" +
                    """<detail><__chat parent="RootContactGroup" groupOwner="false" chatroom="${esc(toCallsign)}" id="${esc(toId)}" senderCallsign="${esc(callsign)}"/>""" +
                    """<remarks source="BAO.F.ATAK.${esc(callsign)}" time="$now" to="${esc(toId)}">${esc(message)}</remarks></detail></event>"""
            }
            else -> null
        }
    }

    private fun isPliType(type: String) =
        type.startsWith("a-f-") || type.startsWith("a-h-") ||
            type.startsWith("a-n-") || type.startsWith("a-u-")

    private fun extractCallsign(xml: String): String? {
        val p = """<contact[^>]*\scallsign\s*=\s*"([^"]*)"""".toRegex()
        return p.find(xml)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    private fun extractCallsignFromUid(uid: String): String {
        val tail = uid.substringAfterLast('-')
        return tail.ifBlank { uid }.take(20)
    }

    private fun extractPointAttr(xml: String, attr: String): Double? {
        val p = """<point[^>]*\s${Regex.escape(attr)}\s*=\s*"([^"]*)"""".toRegex()
        return p.find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun extractElemAttr(xml: String, tag: String, attr: String): String? {
        val p = """<${Regex.escape(tag)}[^>]*\s${Regex.escape(attr)}\s*=\s*"([^"]*)"""".toRegex()
        return p.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun extractRemarksText(xml: String): String? {
        val p = """<remarks[^>]*>([^<]*)</remarks>""".toRegex()
        return p.find(xml)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    private fun extractRole(xml: String): ATAKProtos.MemberRole {
        val role = extractElemAttr(xml, "__group", "role") ?: return ATAKProtos.MemberRole.TeamMember
        return try {
            ATAKProtos.MemberRole.valueOf(role.replace(" ", ""))
        } catch (_: Exception) {
            ATAKProtos.MemberRole.TeamMember
        }
    }

    private fun extractTeam(xml: String): ATAKProtos.Team {
        val team = extractElemAttr(xml, "__group", "name") ?: return ATAKProtos.Team.Cyan
        return try {
            ATAKProtos.Team.valueOf(team.replace(" ", "_"))
        } catch (_: Exception) {
            ATAKProtos.Team.Cyan
        }
    }

    private fun roleString(role: ATAKProtos.MemberRole): String = when (role) {
        ATAKProtos.MemberRole.TeamMember -> "Team Member"
        ATAKProtos.MemberRole.TeamLead -> "Team Lead"
        ATAKProtos.MemberRole.HQ -> "HQ"
        ATAKProtos.MemberRole.Sniper -> "Sniper"
        ATAKProtos.MemberRole.Medic -> "Medic"
        ATAKProtos.MemberRole.ForwardObserver -> "Forward Observer"
        ATAKProtos.MemberRole.RTO -> "RTO"
        ATAKProtos.MemberRole.K9 -> "K9"
        else -> "Team Member"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
