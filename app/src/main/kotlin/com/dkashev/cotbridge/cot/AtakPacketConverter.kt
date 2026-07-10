package com.dkashev.cotbridge.cot

import org.meshtastic.proto.Contact
import org.meshtastic.proto.GeoChat
import org.meshtastic.proto.Group
import org.meshtastic.proto.MemberRole
import org.meshtastic.proto.PLI
import org.meshtastic.proto.TAKPacket
import org.meshtastic.proto.Team
import java.time.Instant
import java.util.Locale

/**
 * Конвертер CoT XML (формат ATAK) ↔ Wire-generated [TAKPacket] (формат мешa).
 *
 * Поддерживает два сценария: PLI (типы `a-f-*`, `a-h-*`, `a-n-*`, `a-u-*`) и GeoChat (тип `b-t-f`).
 * Прочие CoT-типы (маркеры, draw, casevac и т.д.) пропускаются — bandwidth LoRa их не вытянет,
 * а в legacy TAKPacket для них нет полей.
 */
object AtakPacketConverter {

    fun cotToTakPacket(xml: String): TAKPacket? {
        val type = CotXml.extractType(xml) ?: return null
        val uid = CotXml.extractUid(xml) ?: return null
        val callsign = extractCallsign(xml) ?: extractCallsignFromUid(uid)

        val contact = Contact(callsign = callsign.take(20), device_callsign = callsign.take(20))
        val group = Group(role = parseRole(xml), team = parseTeam(xml))

        return when {
            isPliType(type) -> {
                val lat = extractPointAttr(xml, "lat") ?: return null
                val lon = extractPointAttr(xml, "lon") ?: return null
                val hae = extractPointAttr(xml, "hae") ?: 0.0
                val speed = extractElemAttr(xml, "track", "speed")?.toDoubleOrNull() ?: 0.0
                val course = extractElemAttr(xml, "track", "course")?.toDoubleOrNull() ?: 0.0
                val pli = PLI(
                    latitude_i = (lat * COORD_SCALE).toInt(),
                    longitude_i = (lon * COORD_SCALE).toInt(),
                    altitude = hae.toInt(),
                    speed = speed.toInt().coerceAtLeast(0),
                    course = course.toInt().coerceAtLeast(0).coerceAtMost(360),
                )
                TAKPacket(is_compressed = false, contact = contact, group = group, pli = pli)
            }
            type == "b-t-f" -> {
                val message = extractRemarksText(xml) ?: return null
                val to = extractElemAttr(xml, "__chat", "id")
                val toCallsign = extractElemAttr(xml, "__chat", "chatroom")
                val chat = GeoChat(
                    message = message.take(MAX_CHAT_LEN),
                    to = to,
                    to_callsign = toCallsign,
                )
                TAKPacket(is_compressed = false, contact = contact, group = group, chat = chat)
            }
            else -> null
        }
    }

    /** PLI/chat от мешевого юзера X — отправляется через cert юзера X (UID = MESH-X). */
    fun takPacketToCot(packet: TAKPacket): String? {
        val callsign = packet.callsign()
        val teamName = packet.teamName()
        val roleName = packet.roleString()
        val now = Instant.now().toString()
        val stale = Instant.now().plusSeconds(STALE_SECONDS).toString()

        val pli = packet.pli
        val chat = packet.chat
        return when {
            pli != null -> {
                val lat = pli.latitude_i / COORD_SCALE
                val lon = pli.longitude_i / COORD_SCALE
                val uid = "MESH-$callsign"
                """<event version="2.0" uid="${esc(uid)}" type="a-f-G-U-C" time="$now" start="$now" stale="$stale" how="m-g">""" +
                    """<point lat="${"%.7f".format(Locale.US, lat)}" lon="${"%.7f".format(Locale.US, lon)}" hae="${pli.altitude}" ce="9999999" le="9999999"/>""" +
                    """<detail><contact callsign="${esc(callsign)}"/><__group name="${esc(teamName)}" role="${esc(roleName)}"/>""" +
                    """<track speed="${pli.speed}" course="${pli.course}"/></detail></event>"""
            }
            chat != null -> chatCot(callsign, chat.message, chat.to, chat.to_callsign, now, stale)
            else -> null
        }
    }

    /**
     * Fallback для юзеров без cert'а: PLI конвертим в маркер `u-d-p` (User-Defined Point).
     * URPC не ремапит UID маркеров — точки не прыгают и видны на карте под callsign'ом юзера.
     * Минус: на URPC выглядит как маркер (флажок), не как иконка пользователя.
     */
    fun takPacketToMarkerCot(packet: TAKPacket): String? {
        val pli = packet.pli ?: return null
        val callsign = packet.callsign()
        val lat = pli.latitude_i / COORD_SCALE
        val lon = pli.longitude_i / COORD_SCALE
        val uid = "MESH-MARKER-$callsign"
        val now = Instant.now().toString()
        val stale = Instant.now().plusSeconds(STALE_SECONDS * 2).toString()
        return """<event version="2.0" uid="${esc(uid)}" type="u-d-p" time="$now" start="$now" stale="$stale" how="h-g-i-g-o">""" +
            """<point lat="${"%.7f".format(Locale.US, lat)}" lon="${"%.7f".format(Locale.US, lon)}" hae="${pli.altitude}" ce="9999999" le="9999999"/>""" +
            """<detail><contact callsign="${esc(callsign)}"/>""" +
            """<remarks>mesh user (no cert) · speed=${pli.speed} crs=${pli.course}</remarks></detail></event>"""
    }

    /**
     * Fallback-чат от юзера без cert'а: летит под моим аккаунтом, но с префиксом `[callsign]`,
     * чтобы автор был виден в тексте.
     */
    fun takPacketToPrefixedChatCot(packet: TAKPacket, myCallsign: String): String? {
        val chat = packet.chat ?: return null
        val author = packet.callsign()
        val now = Instant.now().toString()
        val stale = Instant.now().plusSeconds(STALE_SECONDS).toString()
        return chatCot(
            sender = myCallsign,
            message = "[$author] ${chat.message}",
            to = chat.to,
            toCallsign = chat.to_callsign,
            now = now,
            stale = stale,
        )
    }

    private fun chatCot(
        sender: String,
        message: String,
        to: String?,
        toCallsign: String?,
        now: String,
        stale: String,
    ): String {
        val toId = to ?: "All Chat Rooms"
        val toCs = toCallsign ?: "All Chat Rooms"
        // Детерминированный UID вместо nanoTime — чтобы два шлюза не задвоили один чат на сервере.
        val chatUid = ChatUid.of(sender, message, Instant.now().epochSecond)
        return """<event version="2.0" uid="${esc(chatUid)}" type="b-t-f" time="$now" start="$now" stale="$stale" how="h-g-i-g-o">""" +
            """<point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>""" +
            """<detail><__chat parent="RootContactGroup" groupOwner="false" chatroom="${esc(toCs)}" id="${esc(toId)}" senderCallsign="${esc(sender)}"/>""" +
            """<remarks source="BAO.F.ATAK.${esc(sender)}" time="$now" to="${esc(toId)}">${esc(message)}</remarks></detail></event>"""
    }

    /** Callsign из `<contact callsign="...">` серверного CoT (для фильтра эха обратки). */
    fun callsignFromCot(xml: String): String? = extractCallsign(xml)

    fun TAKPacket.callsign(): String = contact?.callsign?.ifEmpty { null } ?: "Mesh"
    private fun TAKPacket.teamName(): String = group?.team?.name?.replace("_", " ") ?: "Cyan"
    private fun TAKPacket.roleString(): String = group?.role?.let(::roleString) ?: "Team Member"

    private fun isPliType(type: String) =
        type.startsWith("a-f-") || type.startsWith("a-h-") ||
            type.startsWith("a-n-") || type.startsWith("a-u-")

    private fun extractCallsign(xml: String): String? =
        """<contact[^>]*\scallsign\s*=\s*"([^"]*)""""
            .toRegex().find(xml)?.groupValues?.getOrNull(1)?.ifBlank { null }

    private fun extractCallsignFromUid(uid: String): String =
        uid.substringAfterLast('-').ifBlank { uid }.take(20)

    private fun extractPointAttr(xml: String, attr: String): Double? =
        """<point[^>]*\s${Regex.escape(attr)}\s*=\s*"([^"]*)""""
            .toRegex().find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun extractElemAttr(xml: String, tag: String, attr: String): String? =
        """<${Regex.escape(tag)}[^>]*\s${Regex.escape(attr)}\s*=\s*"([^"]*)""""
            .toRegex().find(xml)?.groupValues?.getOrNull(1)

    private fun extractRemarksText(xml: String): String? =
        """<remarks[^>]*>([^<]*)</remarks>"""
            .toRegex().find(xml)?.groupValues?.getOrNull(1)?.ifBlank { null }

    private fun parseRole(xml: String): MemberRole {
        val role = extractElemAttr(xml, "__group", "role") ?: return MemberRole.TeamMember
        return when (role.replace(" ", "").lowercase()) {
            "teamlead" -> MemberRole.TeamLead
            "teammember" -> MemberRole.TeamMember
            "hq" -> MemberRole.HQ
            "sniper" -> MemberRole.Sniper
            "medic" -> MemberRole.Medic
            "forwardobserver" -> MemberRole.ForwardObserver
            "rto" -> MemberRole.RTO
            "k9" -> MemberRole.K9
            else -> MemberRole.TeamMember
        }
    }

    private fun parseTeam(xml: String): Team {
        val name = extractElemAttr(xml, "__group", "name") ?: return Team.Cyan
        return when (name.replace(" ", "_").lowercase()) {
            "white" -> Team.White
            "yellow" -> Team.Yellow
            "orange" -> Team.Orange
            "magenta" -> Team.Magenta
            "red" -> Team.Red
            "maroon" -> Team.Maroon
            "purple" -> Team.Purple
            "dark_blue" -> Team.Dark_Blue
            "blue" -> Team.Blue
            "cyan" -> Team.Cyan
            "teal" -> Team.Teal
            "green" -> Team.Green
            "dark_green" -> Team.Dark_Green
            "brown" -> Team.Brown
            else -> Team.Cyan
        }
    }

    private fun roleString(role: MemberRole): String = when (role) {
        MemberRole.TeamLead -> "Team Lead"
        MemberRole.HQ -> "HQ"
        MemberRole.Sniper -> "Sniper"
        MemberRole.Medic -> "Medic"
        MemberRole.ForwardObserver -> "Forward Observer"
        MemberRole.RTO -> "RTO"
        MemberRole.K9 -> "K9"
        else -> "Team Member"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val COORD_SCALE = 1e7
    private const val MAX_CHAT_LEN = 200
    private const val STALE_SECONDS = 75L
}
