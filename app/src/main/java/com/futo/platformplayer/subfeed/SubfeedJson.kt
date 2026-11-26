package com.futo.platformplayer.subfeed

import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.Serializer
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.Thumbnail
import com.futo.platformplayer.api.media.models.Thumbnails
import com.futo.platformplayer.api.media.models.contents.ContentType
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.models.HistoryVideo
import com.futo.platformplayer.models.Subscription
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The server's queue as the bridge reads it. */
data class QueueView(val version: Long?, val importing: Boolean, val items: List<JsonObject>) {
    val urls: List<String> get() = items.mapNotNull { SubfeedJson.str(it, "url") }
}

/** The wire shapes of the subfeed API (see the server's README), built from and read into Grayjay's models. */
object SubfeedJson {
    fun iso(time: OffsetDateTime?): String? {
        if (time == null || time.year < 1971 || time.year > 9000)
            return null
        return time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    fun isoNow(): String = iso(OffsetDateTime.now(ZoneOffset.UTC))!!

    fun parseTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank())
            return null
        return try { OffsetDateTime.parse(value) } catch (e: Throwable) {
            try { Instant.parse(value).atOffset(ZoneOffset.UTC) } catch (e2: Throwable) { null }
        }
    }

    fun str(obj: JsonElement?, key: String): String? = ((obj as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun long(obj: JsonElement?, key: String): Long? = ((obj as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    fun video(v: SerializedPlatformVideo, addedAt: OffsetDateTime? = null): JsonObject = buildJsonObject {
        put("url", v.url)
        put("video_id", v.id.value)
        put("title", v.name)
        put("author_name", v.author.name)
        put("author_url", v.author.url)
        put("duration_s", v.duration)
        put("thumbnail", v.thumbnails.getHQThumbnail())
        put("added_at", iso(addedAt))
        //The whole Grayjay object, so a restore brings the entry back exactly as it was
        put("video", Serializer.json.encodeToJsonElement(SerializedPlatformVideo.serializer(), v))
    }

    fun history(h: HistoryVideo): JsonObject = buildJsonObject {
        put("url", h.video.url)
        put("video_id", h.video.id.value)
        put("title", h.video.name)
        put("author_url", h.video.author.url)
        put("author_name", h.video.author.name)
        put("position_s", h.position)
        put("duration_s", h.video.duration)
        put("at", iso(h.date) ?: isoNow())
    }

    fun subscription(s: Subscription): JsonObject = buildJsonObject {
        put("url", s.channel.url)
        put("name", s.channel.name)
        put("created", iso(s.creationTime))
    }

    fun removal(url: String, at: OffsetDateTime): JsonObject = buildJsonObject {
        put("url", url)
        put("at", iso(at) ?: isoNow())
    }

    fun parseQueue(json: JsonElement?): QueueView {
        val obj = json as? JsonObject ?: return QueueView(null, false, listOf())
        val importing = (obj["importing"] as? JsonPrimitive)?.contentOrNull == "true"
        val items = (obj["items"] as? JsonArray)?.mapNotNull {
            when (it) {
                is JsonObject -> it
                is JsonPrimitive -> JsonObject(mapOf("url" to it))
                else -> null
            }
        } ?: listOf()
        return QueueView(long(obj, "version"), importing, items)
    }

    /** A list from either a bare array or {"items": [...]} (also "snapshots"). */
    fun items(json: JsonElement?, vararg keys: String): List<JsonObject> {
        val array = when (json) {
            is JsonArray -> json
            is JsonObject -> (listOf("items") + keys).firstNotNullOfOrNull { json[it] as? JsonArray }
            else -> null
        } ?: return listOf()
        return array.filterIsInstance<JsonObject>()
    }

    /**
     * A Watch Later entry from the wire: the full Grayjay object when the entry carries one (snapshots, backups),
     * otherwise built from the plain fields. Null when there isn't even a title to show.
     */
    fun toVideo(item: JsonObject): SerializedPlatformVideo? {
        (item["video"] as? JsonObject)?.let {
            try {
                return Serializer.json.decodeFromJsonElement(SerializedPlatformVideo.serializer(), it)
            } catch (_: Throwable) {
            }
        }
        val url = str(item, "url") ?: return null
        val title = str(item, "title") ?: str(item, "name") ?: return null
        val authorName = str(item, "author_name") ?: str(item, "author") ?: str(item, "channel") ?: ""
        val authorUrl = str(item, "author_url") ?: str(item, "channel_url") ?: ""
        val thumbnail = str(item, "thumbnail")
        return SerializedPlatformVideo(
            ContentType.MEDIA,
            id = str(item, "video_id")?.let { PlatformID("subfeed", it) } ?: PlatformID.asUrlID(url),
            name = title,
            thumbnails = if (thumbnail != null) Thumbnails(arrayOf(Thumbnail(thumbnail, 0))) else Thumbnails(),
            author = PlatformAuthorLink(PlatformID.NONE, authorName, authorUrl),
            datetime = parseTime(str(item, "published") ?: str(item, "datetime")),
            url = url,
            shareUrl = url,
            duration = long(item, "duration_s") ?: 0,
            viewCount = 0
        )
    }
}
