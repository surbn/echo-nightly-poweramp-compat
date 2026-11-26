package dev.brahmkshatriya.echo.extensions.builtin.offline

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.media3.common.util.UnstableApi
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceUriImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension.Companion.EXTENSION_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class OfflineVideoExtension(
    private val context: Context,
) : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient, LibraryFeedClient, SearchFeedClient {

    companion object {
        val metadata = Metadata(
            className = "OfflineVideoExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.VIDEO,
            id = "echo-offline-video",
            name = "Offline Videos",
            description = "Browse and play local videos stored on your device.",
            version = "v${BuildConfig.VERSION_CODE}",
            author = "Echo",
            icon = R.drawable.ic_offline_video.toResourceImageHolder(),
        )
    }

    private data class VideoItem(
        val id: Long,
        val title: String,
        val duration: Long,
        val path: String,
        val folder: String,
        val resolution: String?,
        val dateModified: Long
    ) {
        fun toTrack(): Track {
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
            )
            val streamable = Streamable.server(
                id = contentUri.toString(),
                quality = 1080,
                title = resolution ?: "Video",
                extras = mapOf("path" to path, "is_video" to "true")
            )
            return Track(
                id = id.toString(),
                title = title,
                type = Track.Type.HorizontalVideo,
                duration = duration,
                cover = contentUri.toString().toResourceUriImageHolder(),
                artists = listOf(Artist(name = folder, id = folder, extras = mapOf(EXTENSION_ID to metadata.id))),
                album = Album(id = folder, title = folder, extras = mapOf(EXTENSION_ID to metadata.id)),
                streamables = listOf(streamable),
                extras = mapOf(
                    EXTENSION_ID to metadata.id,
                    "is_video" to "true",
                    "path" to path
                )
            )
        }
    }

    private suspend fun queryVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Video.Media.DATA,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.RESOLUTION else MediaStore.Video.Media.DATA
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        runCatching {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME) else -1
                val resCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = if (titleCol != -1) cursor.getStringOrNull(titleCol) else null
                    val displayName = if (nameCol != -1) cursor.getStringOrNull(nameCol) else null
                    val title = rawTitle?.takeIf { it.isNotBlank() }
                        ?: displayName?.substringBeforeLast('.')
                        ?: "Video $id"
                    val duration = if (durCol != -1) cursor.getLongOrNull(durCol) ?: 0L else 0L
                    val path = if (dataCol != -1) cursor.getStringOrNull(dataCol).orEmpty() else ""
                    val dateModified = if (dateCol != -1) cursor.getLongOrNull(dateCol) ?: 0L else 0L
                    val folder = if (bucketCol != -1) cursor.getStringOrNull(bucketCol)
                        ?: File(path).parentFile?.name ?: "Videos"
                    else File(path).parentFile?.name ?: "Videos"
                    val resolution = if (resCol != -1) cursor.getStringOrNull(resCol) else null

                    list.add(VideoItem(id, title, duration, path, folder, resolution, dateModified))
                }
            }
        }
        list
    }

    override suspend fun loadTrack(track: Track): Track {
        return track
    }

    override suspend fun loadStreamableMedia(track: Track, streamable: Streamable): Streamable.Media {
        val contentUri = ContentUris.withAppendedId(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, track.id.toLongOrNull() ?: 0L
        )
        val source = contentUri.toString().toSource(
            type = Streamable.SourceType.Progressive,
            isVideo = true
        )
        return source.toMedia()
    }

    override suspend fun loadAlbum(album: Album): Album {
        val videos = queryVideos().filter { it.folder == album.id }
        val tracks = videos.map { it.toTrack() }
        return album.copy(
            tracks = tracks,
            trackCount = tracks.size.toLong(),
            duration = tracks.sumOf { it.duration ?: 0 }
        )
    }

    override suspend fun loadHomeFeed(): Feed {
        return Feed(
            listOf(Tab("All Videos", "All Videos"), Tab("Folders", "Folders"))
        ) { tab ->
            val videos = queryVideos()
            when (tab?.id) {
                "Folders" -> {
                    val folders = videos.groupBy { it.folder }
                    val shelves = folders.map { (folderName, items) ->
                        val album = Album(
                            id = folderName,
                            title = folderName,
                            cover = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, items.first().id
                            ).toString().toResourceUriImageHolder(),
                            trackCount = items.size.toLong(),
                            duration = items.sumOf { it.duration },
                            extras = mapOf(EXTENSION_ID to metadata.id)
                        )
                        Shelf.Lists.Items(
                            folderName,
                            PagedData.Single { items.map { EchoMediaItem.TrackItem(it.toTrack()) } },
                            EchoMediaItem.Lists.AlbumItem(album)
                        )
                    }
                    PagedData.Single { shelves }
                }
                else -> {
                    val tracks = videos.map { EchoMediaItem.TrackItem(it.toTrack()) }
                    PagedData.Single {
                        listOf(Shelf.Lists.Tracks("All Videos", PagedData.Single { tracks }))
                    }
                }
            }
        }
    }

    override suspend fun loadLibraryFeed(): Feed {
        return loadHomeFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed {
        return Feed(
            listOf(Tab("Videos", "Videos"))
        ) {
            val videos = queryVideos()
            val filtered = if (query.isBlank()) videos else videos.filter {
                it.title.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true)
            }
            val tracks = filtered.map { EchoMediaItem.TrackItem(it.toTrack()) }
            PagedData.Single {
                listOf(Shelf.Lists.Tracks("Videos", PagedData.Single { tracks }))
            }
        }
    }
}
