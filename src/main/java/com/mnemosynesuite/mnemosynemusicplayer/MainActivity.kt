package com.mnemosynesuite.mnemosynemusicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.Environment
import android.provider.Settings
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.imageview.ShapeableImageView
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvBreadcrumbHome: TextView
    private lateinit var tvBreadcrumbArtists: TextView
    private lateinit var ivBreadcrumbSeparator: ImageView
    private lateinit var ivBreadcrumbArtists: ImageView

    private val navigationStack = mutableListOf<BreadcrumbNode>()
    private lateinit var addressBarContainer: android.widget.LinearLayout

    // Service Management Variables
    private var musicService: MusicService? = null
    private var isBound = false

    private lateinit var btnMediaPlay: ImageButton
    private lateinit var nowPlayingOverlayContainer: FrameLayout

    private lateinit var npPlaybackSeekBar: SeekBar
    private lateinit var npTextTimeElapsed: TextView
    private lateinit var npTextTimeTotal: TextView
    private lateinit var npBtnPlayPause: ImageButton
    private lateinit var npBtnStop: ImageButton

    private val seekHandler = Handler(Looper.getMainLooper())
    private var isUserTrackingSeekBar = false
    private var lastSeekBarProgress = 0

    private val selectedNowPlayingLayout = "disc" // classic, disc
    private val hideNpDateLabel = true

    private val trackingHandler = Handler(Looper.getMainLooper())
    private var trackingRunnable: Runnable? = null

    private var cdPlaybackAnimator: android.view.animation.Animation? = null

    data class BreadcrumbNode(
        val name: String,
        val destination: NavDestination,
        val iconResId: Int,
        val associatedData: Any? = null
    )

    enum class NavDestination { HOME, ARTISTS_LIST, ALBUMS_LIST, SONGS_LIST }

    val npButtonRowLayout = "central" // getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).getString("now_playing_button_layout", "classic")

    val npButtonRowlayoutRes = when (npButtonRowLayout) {
        "central" -> R.layout.now_playing_buttons_central
        else      -> R.layout.now_playing_buttons_classic
    }

    val npButtonRowlayoutResHeight = when (npButtonRowLayout) {
        "central" -> 110
        else      -> 90
    }

    fun npCDapplyJacketOverlayEffect(imageView: ImageView, albumArtDrawable: Drawable) {
        val context = imageView.context

        val glossOverlay = ContextCompat.getDrawable(context, R.drawable.disc_jacket) ?: return

        val layers = arrayOf(
            albumArtDrawable, // Index 0: Background dynamic album cover art
            glossOverlay      // Index 1: Vintage front jacket glass/plastic overlay
        )

        val compositeLayerDrawable = LayerDrawable(layers).apply {
            // Keeps padding modes unified within the stack structure
            paddingMode = LayerDrawable.PADDING_MODE_STACK

            // Retrieve the exact intrinsic pixel height of your disc_jacket asset
            val overlayHeight = glossOverlay.intrinsicHeight
            val overlayWidth = glossOverlay.intrinsicWidth

            if (overlayHeight > 0) {
                // Calculate 98% of the target height for the underlying cover art
                val targetCoverHeight = (overlayHeight * 0.98f).toInt()

                // Configure Layer 0 (Album Art):
                // Width is set to -1 (keeps its default dimension scaling unforced)
                setLayerSize(0, targetCoverHeight, targetCoverHeight)

                // Anchor it vertically centered but pushed flush against the right edge
                setLayerGravity(0, android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END)

                // Configure Layer 1 (Jacket Overlay):
                // Lock its height explicitly to its natural bounds
                setLayerSize(1, -1, overlayHeight)
                setLayerGravity(1, android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END)

                // 🟢 Calculate exactly 1% of the jacket's full native width in pixels
                val rightMarginPercent = (overlayWidth * 0.02f).toInt()

                // Apply the dynamic 1% inset to the right side of the jacket overlay
                setLayerInsetRight(1, -rightMarginPercent)
            }
        }

        // Push the compiled matrix to your ImageView
        imageView.setImageDrawable(compositeLayerDrawable)
    }

    private fun renderBreadcrumbs() {
        // Clear out old nodes completely
        addressBarContainer.removeAllViews()

        navigationStack.forEachIndexed { index, node ->
            // 1. Create and add Node Icon
            val iconView = ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    dimenDpToPx(24), dimenDpToPx(24)
                ).apply {
                    marginEnd = dimenDpToPx(6)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(node.iconResId)
            }
            addressBarContainer.addView(iconView)

            // 2. Create and add Node Label Text
            val textView = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = node.name
                textSize = 14f

                // Highlight active step white, set previous paths to a muted alpha
                setTextColor(if (index == navigationStack.lastIndex) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt())

                if (index < navigationStack.lastIndex) {
                    setOnClickListener {
                        // Unwind the history tracking stack down to the target click point
                        while (navigationStack.size > index + 1) {
                            navigationStack.removeAt(navigationStack.lastIndex)
                        }
                        navigateByDestination(node.destination, node.associatedData)
                    }
                }
            }
            addressBarContainer.addView(textView)

            // 3. Append standard Separator Arrow if this isn't the active leaf layout node
            if (index < navigationStack.lastIndex) {
                val separator = ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dimenDpToPx(18), dimenDpToPx(18)
                    ).apply {
                        this.gestureIndicators(8, 8) // apply start and end margins
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageResource(R.drawable.t_breadcrumb) // Matches your default arrow layout asset
                }
                addressBarContainer.addView(separator)
            }
        }


        val scrollView = findViewById<android.widget.HorizontalScrollView>(R.id.addressBarScrollView)
        scrollView?.post {
            scrollView.fullScroll(View.FOCUS_RIGHT)
        }
    }

    // Helper utilities to format Layout margins programmatically
    private fun android.widget.LinearLayout.LayoutParams.gestureIndicators(start: Int, end: Int) {
        this.marginStart = dimenDpToPx(start)
        this.marginEnd = dimenDpToPx(end)
    }

    private fun dimenDpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun navigateByDestination(destination: NavDestination, data: Any?) {
        when (destination) {
            NavDestination.HOME -> {
                recyclerView.adapter = UniversalLibraryAdapter(listOf("Artists", "Albums", "Playlists", "Genres"), TYPE_CATEGORY) {
                    if (it == "Artists") loadArtistsList() else Toast.makeText(this, "$it path under development.", Toast.LENGTH_SHORT).show()
                }
            }
            NavDestination.ARTISTS_LIST -> {
                recyclerView.adapter = UniversalLibraryAdapter(albumArtistList(), TYPE_ARTIST) { loadAlbumsList(it as String) }
            }
            NavDestination.ALBUMS_LIST -> {
                val artistName = data as String
                recyclerView.adapter = UniversalLibraryAdapter(filterAlbumsByAlbumArtist(artistName), TYPE_ALBUM) {
                    val album = it as AudioAlbum
                    loadSongsList(album.albumArtist, album.name)
                }
            }
            NavDestination.SONGS_LIST -> {
                val (artistName, albumName) = data as Pair<String, String>
                recyclerView.adapter = UniversalLibraryAdapter(filterSongsByAlbumAndAlbumArtist(artistName, albumName), TYPE_SONG) {
                    musicService?.executePlayback(it as AudioTrack)
                }
            }
        }

        // --- ANIMATE THE ENTIRE CONTAINER ---
        // Load the 300ms fade + slide animation
        val containerAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.list_item_fade_slide)
        // Run it on the RecyclerView itself
        recyclerView.startAnimation(containerAnim)

        renderBreadcrumbs()
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            musicService?.mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    if(!isUserTrackingSeekBar) {
                        val currentPos = player.currentPosition
                        npPlaybackSeekBar.progress = currentPos
                        npTextTimeElapsed.text = formatTimeString(currentPos)
                    }
                    startCDPlaybackSpinning()
                }
            }
            seekHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true

            // Set up listeners to communicate framework events back to the UI activity
            musicService?.onTrackPreparedListener = { track ->
                npPlaybackSeekBar.max = musicService?.mediaPlayer?.duration ?: 0
                btnMediaPlay.setImageResource(R.drawable.button_pause)
                updateNowPlayingInfo(track)
                seekHandler.post(progressUpdater)
            }

            musicService?.onTrackCompletedListener = {
                btnMediaPlay.setImageResource(android.R.drawable.ic_media_play)
                npBtnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekHandler.removeCallbacks(progressUpdater)

                stopCDPlaybackSpinning()
            }

            musicService?.onSeekCompletedListener = { currentPos ->
                if (selectedNowPlayingLayout == "disc") {
                    val emptyViewport = nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)
                    val cdImageView = emptyViewport?.findViewById<ImageView>(R.id.npDiscCD)

                    if (cdImageView != null) {
                        val deltaProgress = currentPos - lastSeekBarProgress

                        // Adjust multiplier for the flick magnitude on seek completion
                        val rotationStep = deltaProgress * 0.01f
                        val targetRotation = cdImageView.rotation + rotationStep

                        // Force kill any ongoing tracking remnants and flick smoothly
                        cdImageView.animate().cancel()
                        cdImageView.animate()
                            .rotation(targetRotation)
                            .setDuration(1000) // 500ms smooth ease-out stabilization
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()

                        // Lock the progress milestone baseline
                        lastSeekBarProgress = currentPos
                    }
                }
            }

            // Sync the interface instantly if a track is already running when returning home
            syncUiWithServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    private val storagePrefix: String = android.os.Environment.getExternalStorageDirectory().absolutePath
//    private val musicDatabase = listOf(
//        AudioTrack("The Moment", "Coptic Rain", "Coptic Rain", "cr", 1, "Industrial Rock", "1995", "$storagePrefix/SampleMusic/cr/01 - The Moment.mp3", 225),
//        AudioTrack("Unseen-Untold", "Coptic Rain", "Coptic Rain", "cr", 2, "Industrial Rock", "1995", "$storagePrefix/SampleMusic/cr/02 - Unseen-Untold.mp3", 252),
//        AudioTrack("Sane", "Coptic Rain", "Coptic Rain", "cr", 3, "Industrial Rock", "1995", "$storagePrefix/SampleMusic/cr/03 - Sane.mp3", 198),
//        AudioTrack("Double Edge", "Coptic Rain", "Coptic Rain", "cr", 4, "Industrial Rock", "1995", "$storagePrefix/SampleMusic/cr/04 - Double Edge.mp3", 239),
//        AudioTrack("FREEDOM", "Kesha", "Kesha", "kesha", 1, "Pop", "2026", "$storagePrefix/SampleMusic/kesha/01. FREEDOM.mp3", 212),
//        AudioTrack("JOYRIDE", "Kesha", "Kesha", "kesha", 2, "Pop", "2026", "$storagePrefix/SampleMusic/kesha/02. JOYRIDE.mp3", 175),
//        AudioTrack("YIPPEE-KI-YAY", "Kesha", "Kesha", "kesha", 3, "Pop", "2026", "$storagePrefix/SampleMusic/kesha/03. YIPPEE-KI-YAY.mp3", 184),
//        AudioTrack("Disease", "Lady Gaga", "Lady Gaga", "MAYHEM", 1, "Pop", "2024", "$storagePrefix/SampleMusic/lgaga/01 - Disease.mp3", 298),
//        AudioTrack("Abracadabra", "Lady Gaga", "Lady Gaga", "MAYHEM", 2, "Pop", "2026", "$storagePrefix/SampleMusic/lgaga/02 - Abracadabra.mp3", 202),
//        AudioTrack("Garden Of Eden", "Lady Gaga; Gesaffelstein", "Lady Gaga", "MAYHEM", 3, "Pop", "2026", "$storagePrefix/SampleMusic/lgaga/01 - Garden Of Eden (Demo Stem Mix).mp3", 221),
//        AudioTrack("Perfect Celebrity", "Lady Gaga", "Lady Gaga", "MAYHEM", 4, "Pop", "2026", "$storagePrefix/SampleMusic/lgaga/04 - Perfect Celebrity.mp3", 195),
//        AudioTrack("I Feel So Free", "Madonna", "Madonna", "madonna", 1, "Pop / Dance", "2026", "$storagePrefix/SampleMusic/madonna/01 - I Feel So Free.mp3", 260),
//        AudioTrack("Bring Your Love", "Madonna; Sabrina Carpenter", "Madonna", "madonna", 4, "Pop / Dance", "2026", "$storagePrefix/SampleMusic/madonna/04 - Bring Your Love.mp3", 234),
//        AudioTrack("Love Sensation", "Madonna", "Madonna", "madonna", 8, "Pop / Dance", "2026", "$storagePrefix/SampleMusic/madonna/08 - Love Sensation.mp3", 242),
//        AudioTrack("Mr. Self Destruct", "Nine Inch Nails", "Nine Inch Nails", "The Downward Spiral", 1, "Industrial Rock", "1994", "$storagePrefix/SampleMusic/nin/01 - Mr. Self Destruct.mp3", 270),
//        AudioTrack("Piggy", "Nine Inch Nails", "Nine Inch Nails", "The Downward Spiral", 2, "Industrial Rock", "1994", "$storagePrefix/SampleMusic/nin/02 - Piggy.mp3", 264),
//        AudioTrack("Heresy", "Nine Inch Nails", "Nine Inch Nails", "The Downward Spiral", 3, "Industrial Rock", "1994", "$storagePrefix/SampleMusic/nin/03 - Heresy.mp3", 234),
//        AudioTrack("March Of The Pigs", "Nine Inch Nails", "Nine Inch Nails", "The Downward Spiral", 4, "Industrial Rock", "1994", "$storagePrefix/SampleMusic/nin/04 - March Of The Pigs.mp3", 178)
//    )

    private var musicDatabase = mutableListOf<AudioTrack>()

    private fun saveDatabaseToStorage() {
        val sharedPreferences = getSharedPreferences("MnemosynePrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        // We can build a lightweight JSON array string representation manually to avoid adding large libraries
        val jsonArrayStringBuilder = StringBuilder()
        jsonArrayStringBuilder.append("[")
        musicDatabase.forEachIndexed { index, track ->
            jsonArrayStringBuilder.append("{")
            jsonArrayStringBuilder.append("\"title\":\"${escapeJson(track.title)}\",")
            jsonArrayStringBuilder.append("\"artist\":\"${escapeJson(track.artist)}\",")
            jsonArrayStringBuilder.append("\"album\":\"${escapeJson(track.album)}\",")
            jsonArrayStringBuilder.append("\"albumArtist\":\"${escapeJson(track.albumArtist)}\",")
            jsonArrayStringBuilder.append("\"track_number\":${track.track_number},")
            jsonArrayStringBuilder.append("\"genre\":\"${escapeJson(track.genre)}\",")
            jsonArrayStringBuilder.append("\"date\":\"${escapeJson(track.date)}\",")
            jsonArrayStringBuilder.append("\"filePath\":\"${escapeJson(track.filePath)}\",")
            jsonArrayStringBuilder.append("\"length\":${track.length}")
            jsonArrayStringBuilder.append("}")
            if (index < musicDatabase.size - 1) jsonArrayStringBuilder.append(",")
        }
        jsonArrayStringBuilder.append("]")

        editor.putString("saved_music_db", jsonArrayStringBuilder.toString())
        editor.apply()
    }

    // Escapes quotes to keep manual JSON string formatting secure
    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    // Load library items on launch if present
    private fun loadDatabaseFromStorage() {
        val sharedPreferences = getSharedPreferences("MnemosynePrefs", Context.MODE_PRIVATE)
        val savedData = sharedPreferences.getString("saved_music_db", null) ?: return

        try {
            musicDatabase.clear()
            // Simple manual parsing routine matching the saved keys
            val regex = "\\{\"title\":\"(.*?)\",\"artist\":\"(.*?)\",\"album\":\"(.*?)\",\"albumArtist\":\"(.*?)\",\"track_number\":(\\d+),\"genre\":\"(.*?)\",\"date\":\"(.*?)\",\"filePath\":\"(.*?)\",\"length\":(\\d+)\\}".toRegex()
            val matches = regex.findAll(savedData)
            for (match in matches) {
                val (title, artist, album, albumArtist, trackNum, genre, date, filePath, length) = match.destructured
                musicDatabase.add(
                    AudioTrack(title, artist, albumArtist, album, trackNum.toInt(), genre, date, filePath, length.toInt())
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Perform sequential recursive scan of the SampleMusic directory
    private fun startMusicDirectoryScan() {
        Toast.makeText(this, "Scanning SampleMusic directory...", Toast.LENGTH_SHORT).show()
        musicDatabase.clear()

        val sharedPreferences = getSharedPreferences("MnemosynePrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().remove("saved_music_db").apply()

        // Run file system operations off the main UI thread to avoid stutter loops
        Thread {
            val targetDir = java.io.File("$storagePrefix/SampleMusic/")
            if (targetDir.exists() && targetDir.isDirectory) {
                scanDirectoryRecursive(targetDir)
            }

            // Save tracks and refresh navigation visual blocks on main UI thread
            runOnUiThread {
                saveDatabaseToStorage()
                Toast.makeText(this, "Scan complete! Found ${musicDatabase.size} tracks.", Toast.LENGTH_SHORT).show()
                goToHomePage() // Reload and refresh standard Home UI contents
            }
        }.start()
    }

    private fun extractRawId3DateTag(file: java.io.File): String? {
        if (!file.exists()) return null
        var inputStream: java.io.FileInputStream? = null
        try {
            inputStream = java.io.FileInputStream(file)
            val header = ByteArray(10)
            if (inputStream.read(header) != 10) return null

            // Check for valid ID3v2 identifier sequence "ID3"
            if (header[0].toInt() != 0x49 || header[1].toInt() != 0x44 || header[2].toInt() != 0x33) return null

            val majorVersion = header[3].toInt()
            // Determine total size of the ID3 tag metadata header container
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)

            val tagBuffer = ByteArray(tagSize)
            inputStream.read(tagBuffer)

            var position = 0
            // Search for ID3 text frame markers: TYER (v2.3 Year), TDAT (v2.3 Date), or TDRC (v2.4 Recording Date)
            while (position < tagSize - 10) {
                val frameId = String(tagBuffer, position, 4, java.nio.charset.StandardCharsets.US_ASCII)

                val frameSize = if (majorVersion == 4) {
                    // ID3v2.4 uses Synchsafe integers for sizing definitions
                    ((tagBuffer[position + 4].toInt() and 0x7F) shl 21) or
                            ((tagBuffer[position + 5].toInt() and 0x7F) shl 14) or
                            ((tagBuffer[position + 6].toInt() and 0x7F) shl 7) or
                            (tagBuffer[position + 7].toInt() and 0x7F)
                } else {
                    // ID3v2.3 uses standard big-endian array bytes
                    ((tagBuffer[position + 4].toInt() and 0xFF) shl 24) or
                            ((tagBuffer[position + 5].toInt() and 0xFF) shl 16) or
                            ((tagBuffer[position + 6].toInt() and 0xFF) shl 8) or
                            (tagBuffer[position + 7].toInt() and 0xFF)
                }

                if (frameSize <= 0 || position + 10 + frameSize > tagSize) break

                // If we match an ID3v2.4 Date frame (TDRC) or an ID3v2.3 frame (TYER / TDAT)
                if (frameId == "TDRC" || frameId == "TYER") {
                    val encodingByte = tagBuffer[position + 10].toInt()
                    val charset = when (encodingByte) {
                        1 -> java.nio.charset.StandardCharsets.UTF_16
                        2 -> java.nio.charset.StandardCharsets.UTF_16BE
                        3 -> java.nio.charset.StandardCharsets.UTF_8
                        else -> java.nio.charset.StandardCharsets.ISO_8859_1
                    }
                    // Extract the value string, skipping structural encoding details
                    val rawValue = String(tagBuffer, position + 11, frameSize - 1, charset).trim()

                    // Clean up string values (e.g. changing 2026:04:18 timestamps to 2026-04-18 layout)
                    if (rawValue.isNotEmpty()) {
                        var cleanDate = rawValue.split("T")[0].replace(":", "-").replace(".", "-")
                        if (cleanDate.matches(Regex("\\d{4}"))) {
                            cleanDate = "$cleanDate-01-01"
                        }
                        if (cleanDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            return cleanDate
                        }
                    }
                }
                position += 10 + frameSize
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
        }
        return null
    }

    private fun scanDirectoryRecursive(directory: java.io.File) {
        val files = directory.listFiles() ?: return
        val retriever = android.media.MediaMetadataRetriever()

        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursive(file)
            } else if (file.isFile && (file.name.endsWith(".mp3", true) || file.name.endsWith(".flac", true))) {
                try {
                    retriever.setDataSource(file.absolutePath)

                    // Read underlying metadata frames
                    val rawTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val rawArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val rawAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val rawAlbumArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    val rawTrackNum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    val rawGenre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                    var rawDate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                    val rawDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)

                    if (rawDate.isNullOrEmpty()) {
                        rawDate = retriever.extractMetadata(5029)
                    }

                    if (rawDate.isNullOrEmpty()) {
                        rawDate = extractRawId3DateTag(file)
                    }

                    // Precise cleanups and fallbacks if items are untagged
                    val title = rawTitle ?: file.nameWithoutExtension
                    val artist = rawArtist ?: "Unknown Artist"
                    val album = rawAlbum ?: "Unknown Album"
                    val albumArtist = if (!rawAlbumArtist.isNullOrEmpty()) rawAlbumArtist else artist
                    val genre = rawGenre ?: "Unknown Genre"
                    val date = rawDate ?: "Unknown Year"
                    val lengthInSeconds = if (!rawDurationMs.isNullOrEmpty()) rawDurationMs.toInt() / 1000 else 0

                    // Track number clean parsing (handles formatting quirks like "1/12" or "02")
                    var trackNumber = 1
                    if (!rawTrackNum.isNullOrEmpty()) {
                        val cleanTrackNum = rawTrackNum.split("/")[0].replace(Regex("[^0-9]"), "")
                        if (cleanTrackNum.isNotEmpty()) trackNumber = cleanTrackNum.toInt()
                    }

                    musicDatabase.add(
                        AudioTrack(title, artist, albumArtist, album, trackNumber, genre, date, file.absolutePath, lengthInSeconds)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        try { retriever.release() } catch (e: Exception) { e.printStackTrace() }
    }

    fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun checkStoragePermissionsAndPrepare() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // If the system hasn't been granted the "All Files Access" toggle yet
            if (!Environment.isExternalStorageManager()) {
                Log.w("FolderPrep", "App lacks All Files Access clearance. Opening Settings panel.")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                // Already authorized! Safe to compile directories
                prepareFolders()
            }
        } else {
            // Legacy versions (Android 9/10 handled by standard runtime requests)
            prepareFolders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        Toast.makeText(this, "$storagePrefix", Toast.LENGTH_SHORT).show()

        prepareFolders()

        checkStoragePermissionsAndPrepare()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. If Now Playing overlay is open, close/hide it
                if (nowPlayingOverlayContainer.visibility == View.VISIBLE) {
                    closeNowPlaying()
                } else {
                    // 2. If we are in the library browser, check our breadcrumb history stack
                    if (navigationStack.size > 1) {
                        // Remove the current active destination leaf node
                        navigationStack.removeAt(navigationStack.lastIndex)

                        // Grab the previous parent node state
                        val previousNode = navigationStack.last()

                        // Re-trigger the list adapter loading without adding a new duplicate stack history row
                        // We briefly trim the last item before executing so navigateByDestination doesn't double-append
                        navigationStack.removeAt(navigationStack.lastIndex)
                        when (previousNode.destination) {
                            NavDestination.HOME -> goToHomePage()
                            NavDestination.ARTISTS_LIST -> loadArtistsList()
                            NavDestination.ALBUMS_LIST -> loadAlbumsList(previousNode.associatedData as String)
                            NavDestination.SONGS_LIST -> {
                                val data = previousNode.associatedData as Pair<String, String>
                                loadSongsList(data.first, data.second)
                            }
                        }
                    } else {
                        // 3. If we are already sitting on the Home root layout node, cleanly minimize task
                        moveTaskToBack(true)
                    }
                }
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Start and Bind to our Playback background engine Service
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Initialize UI Elements
        recyclerView = findViewById(R.id.containerLibraryBrowser)
        recyclerView.layoutManager = LinearLayoutManager(this)

        btnMediaPlay = findViewById(R.id.btnMediaPlay)
        btnMediaPlay.setOnClickListener { togglePlayback() }

        tvBreadcrumbHome = findViewById(R.id.tvBreadcrumbHome)
        tvBreadcrumbArtists = findViewById(R.id.tvBreadcrumbArtists)
        ivBreadcrumbSeparator = findViewById(R.id.ivBreadcrumbSeparator)
        ivBreadcrumbArtists = findViewById(R.id.ivBreadcrumbArtists)

        findViewById<View>(R.id.btnNavHome).setOnClickListener { goToHomePage() }
        tvBreadcrumbHome.setOnClickListener { goToHomePage() }

        findViewById<View>(R.id.bottomPlayerDockCover).setOnClickListener { openNowPlaying() }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO), 101)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }

        nowPlayingOverlayContainer = findViewById(R.id.nowPlayingOverlayContainer)
        layoutInflater.inflate(R.layout.now_playing, nowPlayingOverlayContainer, true)

        val container = findViewById<FrameLayout>(R.id.npButtonsContainer)
        container.removeAllViews()
        layoutInflater.inflate(npButtonRowlayoutRes, container, true)

        val seekBarRow = findViewById<LinearLayout>(R.id.npSeekBarRow)
        val controlDeck = findViewById<LinearLayout>(R.id.npControlDeckPanel)

        controlDeck.layoutParams.height = dpToPx(npButtonRowlayoutResHeight)
        controlDeck.requestLayout()

        val emptyViewport = nowPlayingOverlayContainer.findViewById<ViewGroup>(R.id.npEmptyViewport)

        when (selectedNowPlayingLayout) {
            "classic" -> {
                layoutInflater.inflate(R.layout.now_playing_info_classic, emptyViewport, true)
            }
            "disc" -> {
                layoutInflater.inflate(R.layout.now_playing_info_disc_vertical, emptyViewport, true)
            }
        }

        if(hideNpDateLabel) {
            nowPlayingOverlayContainer.findViewById<LinearLayout>(R.id.rowDate)?.visibility=View.GONE
        }

        npPlaybackSeekBar = nowPlayingOverlayContainer.findViewById(R.id.npPlaybackSeekBar)
        npTextTimeElapsed = nowPlayingOverlayContainer.findViewById(R.id.npTextTimeElapsed)
        npTextTimeTotal = nowPlayingOverlayContainer.findViewById(R.id.npTextTimeTotal)
        npBtnPlayPause = nowPlayingOverlayContainer.findViewById(R.id.npBtnPlayPause)
        npBtnStop = nowPlayingOverlayContainer.findViewById(R.id.npBtnStop)

        npPlaybackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    npTextTimeElapsed.text = formatTimeString(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 2. Schedule the confirmation token 50ms into the future
                isUserTrackingSeekBar = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    musicService?.mediaPlayer?.seekTo(it.progress)
                }
                isUserTrackingSeekBar = false
            }
        })

        npBtnPlayPause.setOnClickListener { togglePlayback() }
        npBtnStop.setOnClickListener { stopPlayback() }

        // Find the single linear address container row from your XML layout
        addressBarContainer = findViewById(R.id.addressBarContainer)

        findViewById<View>(R.id.btnNavHome).setOnClickListener { goToHomePage() }

        // 1. Try loading cached database items from SharedPreferences
        loadDatabaseFromStorage()

        // 2. Bind scan actions to settings row click handler
        findViewById<View>(R.id.settingsRowContainer).setOnClickListener {
            startMusicDirectoryScan()
        }

        // Remove the old updateBreadcrumbs method call at the bottom of onCreate and initialize pathing
        goToHomePage()
    }

    private fun LoadCoverArt(filePath: String, targetViews: List<ImageView>) {
        val retriever = android.media.MediaMetadataRetriever()
        var bitmap: android.graphics.Bitmap? = null

        try {
            retriever.setDataSource(filePath)
            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (ex: Exception) { ex.printStackTrace() }
        }

        // Apply resource updates securely on the main thread loop UI pass
        targetViews.forEach { imageView ->
            if(imageView != null) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.cover) // Retro album asset fallback block
                }
            }
        }
    }

    private fun startCDPlaybackSpinning() {
        if (selectedNowPlayingLayout != "disc") return

        val emptyViewport = nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)
        val cdImageView = emptyViewport?.findViewById<ImageView>(R.id.npDiscCD) ?: return

        // If it's already spinning, don't restart it
        if (cdImageView.animation != null) return

        // Create an infinite 360 rotation over 4 seconds (4000ms) - adjust duration for speed
        val rotate = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 4000
            repeatCount = android.view.animation.Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }

        cdImageView.startAnimation(rotate)
    }

    private fun stopCDPlaybackSpinning() {
        if (selectedNowPlayingLayout != "disc") return

        val emptyViewport = nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)
        val cdImageView = emptyViewport?.findViewById<ImageView>(R.id.npDiscCD)

        // Clear animation instantly to halt spinning on pause/stop
        cdImageView?.clearAnimation()
    }

    private fun syncUiWithServiceState() {
        val activeTrack = musicService?.currentPlayingTrack ?: return
        val player = musicService?.mediaPlayer ?: return

        updateNowPlayingInfo(activeTrack)
        npPlaybackSeekBar.max = player.duration
        npPlaybackSeekBar.progress = player.currentPosition

        val isPlaying = player.isPlaying
        updatePlaybackUiStates(isPlaying)

        if (isPlaying) {
            startCDPlaybackSpinning() // Sync rotation state on viewport re-entry
            seekHandler.post(progressUpdater)
        } else {
            stopCDPlaybackSpinning()
        }
    }

    fun closeNowPlaying() {
        nowPlayingOverlayContainer.animate()
            .alpha(0f)
            .translationY(250f)
            .setDuration(150)
            .withEndAction { nowPlayingOverlayContainer.visibility = View.GONE }
            .start()
    }

    fun openNowPlaying() {
        nowPlayingOverlayContainer.visibility = View.VISIBLE
        nowPlayingOverlayContainer.alpha = 0f
        nowPlayingOverlayContainer.translationY=450f
        val isPlaying = musicService?.isPlaying ?: false
        updatePlaybackUiStates(isPlaying)

        nowPlayingOverlayContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setListener(null)
            .start()
    }

    // --- DATABASE FILTRATION LOGIC ---
    private fun albumArtistList(): List<String> = musicDatabase.map { it.albumArtist }.distinct().sorted()

    private fun filterAlbumsByAlbumArtist(artistName: String): List<AudioAlbum> {
        val artistTracks = musicDatabase.filter { it.albumArtist == artistName }
        return artistTracks.map { it.album }.distinct().map { albumName ->
            val albumTracks = artistTracks.filter { it.album == albumName }
            val maxDate = albumTracks.map { it.date }.maxOrNull() ?: ""
            AudioAlbum(albumName, artistName, maxDate, albumTracks.firstOrNull()?.filePath ?: "")
        }.sortedWith(compareBy { it.date })
    }

    private fun filterSongsByAlbumAndAlbumArtist(artistName: String, albumName: String): List<AudioTrack> {
        return musicDatabase.filter { it.albumArtist == artistName && it.album == albumName }.sortedBy { it.track_number }
    }

    // --- NAVIGATION ROUTINES ---
    private fun goToHomePage() {
        navigationStack.clear()
        navigationStack.add(BreadcrumbNode("Home", NavDestination.HOME, R.drawable.home_glyph))
        navigateByDestination(NavDestination.HOME, null)
    }

    private fun loadArtistsList() {
        navigationStack.add(BreadcrumbNode("Artists", NavDestination.ARTISTS_LIST, R.drawable.t_artist))
        navigateByDestination(NavDestination.ARTISTS_LIST, null)
    }

    private fun loadAlbumsList(artistName: String) {
        navigationStack.add(BreadcrumbNode(artistName, NavDestination.ALBUMS_LIST, R.drawable.t_artist, artistName))
        navigateByDestination(NavDestination.ALBUMS_LIST, artistName)
    }

    private fun loadSongsList(artistName: String, albumName: String) {
        navigationStack.add(BreadcrumbNode(albumName, NavDestination.SONGS_LIST, R.drawable.t_album, Pair(artistName, albumName)))
        navigateByDestination(NavDestination.SONGS_LIST, Pair(artistName, albumName))
    }

    private fun prepareFolders() {
        // 1. Target the root of the internal public shared storage (/storage/emulated/0)
        val rootStorage = Environment.getExternalStorageDirectory()

        // 2. Define the target directories
        val mnemosyneDir = File(rootStorage, "Mnemosyne")
        val musicPlayerDir = File(mnemosyneDir, "MusicPlayer")

        // 3. Create the nested directory path cleanly (mkdir() builds both if missing)
        if (!musicPlayerDir.exists()) {
            val created =
                musicPlayerDir.mkdirs() &&
                File(musicPlayerDir, "disc_images").mkdirs() &&
                File(musicPlayerDir, "lpvinyl_images").mkdirs()

            if (created) {
                Log.d("FolderPrep", "Successfully created: ${musicPlayerDir.absolutePath}")
            } else {
                Log.e("FolderPrep", "Failed to create directory structure.")
            }
        } else {
            Log.d("FolderPrep", "Directories already exist.")
        }

        // 4. Handle the .nomedia file inside /Mnemosyne/
        val noMediaFile = File(mnemosyneDir, ".nomedia")
        if (!noMediaFile.exists()) {
            try {
                val fileCreated = noMediaFile.createNewFile()
                if (fileCreated) {
                    Log.d("FolderPrep", ".nomedia file successfully initialized.")
                }
            } catch (e: IOException) {
                Log.e("FolderPrep", "Error generating .nomedia marker file", e)
            }
        }
    }

    private fun getSha256Hash(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            // Convert byte array to a hex string format
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("SHA256", "Error calculating hash", e)
            ""
        }
    }

    /**
     * Updates the physical CD disc image view with a custom .webp asset
     * hashed using SHA-256 out of the track's meta properties.
     */
    private fun updateCDDrawable(track: AudioTrack) {
        val cdDiscImageView = findViewById<ImageView>(R.id.npDiscCD) ?: return

        val albumArtist = track.artist ?: "Unknown Artist"
        val albumName = track.album ?: "Unknown Album"
        val configurationString = "$albumArtist---$albumName"

        // 1. Calculate the SHA-256 hash signature (shorter hex string than Base64)
        val sha256Filename = getSha256Hash(configurationString)

        if (sha256Filename.isNotEmpty()) {
            // 2. Set up directory references to look into /Mnemosyne/MusicPlayer/disc_images/
            val rootStorage = Environment.getExternalStorageDirectory()
            val mnemosyneDir = File(rootStorage, "Mnemosyne")
            val musicPlayerDir = File(mnemosyneDir, "MusicPlayer")
            val discImagesDir = File(musicPlayerDir, "disc_images")

            val customDiscFile = File(discImagesDir, "$sha256Filename.webp")

            Log.d("Looking for cd image:","Looking for cd image: ${discImagesDir}/$sha256Filename.webp")

            // 3. Apply the custom asset if it exists, otherwise fall back to template resource
            if (customDiscFile.exists()) {
                val customDiscDrawable = Drawable.createFromPath(customDiscFile.absolutePath)
                if (customDiscDrawable != null) {
                    cdDiscImageView.setImageDrawable(customDiscDrawable)
                    Log.d("CDUpdate", "Loaded custom disc art: ${customDiscFile.name}")
                } else {
                    cdDiscImageView.setImageResource(R.drawable.cd)
                }
            } else {
                cdDiscImageView.setImageResource(R.drawable.cd)
            }
        } else {
            cdDiscImageView.setImageResource(R.drawable.cd)
        }
    }

    private fun updateNowPlayingInfo(track: AudioTrack) {
        findViewById<TextView>(R.id.textView).text = track.title
        findViewById<TextView>(R.id.textView2).text = track.artist

        val emptyViewport = nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)

        val imageViewsToUpdate = mutableListOf<ImageView>()

        findViewById<ShapeableImageView>(R.id.bottomPlayerDockCover)?.let { imageViewsToUpdate.add(it) }

        if (emptyViewport != null) {
            // Core Metadata Fields
            emptyViewport.findViewById<TextView>(R.id.tvTrackValue)?.text = track.title
            emptyViewport.findViewById<TextView>(R.id.tvArtistValue)?.text = track.artist
            emptyViewport.findViewById<TextView>(R.id.tvAlbumValue)?.text = track.album
            emptyViewport.findViewById<TextView>(R.id.tvDateValue)?.text = track.date

            emptyViewport.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.nowPlayingCoverArt)?.let {
                imageViewsToUpdate.add(it)
            }

            // Cover Frame Update
            emptyViewport.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.nowPlayingCoverArt)
                ?.setImageResource(R.drawable.cover)

            // Track Index Number Formatting (e.g., "01", "04")
            emptyViewport.findViewById<TextView>(R.id.tvCurrentTrackIndex)?.text =
                String.format("%02d", track.track_number)

            // Find total number of songs matching this specific album in your database
            val totalTracksInAlbum = musicDatabase.count {
                it.albumArtist == track.albumArtist && it.album == track.album
            }
            emptyViewport.findViewById<TextView>(R.id.tvTotalTracksCount)?.text = "/$totalTracksInAlbum"

            // Dynamic Status Glyphs (Updates whether it displays play or pause graphic inside view)
            val isPlaying = musicService?.mediaPlayer?.isPlaying == true
            emptyViewport.findViewById<ImageView>(R.id.ivPlaybackIndicator)?.setImageResource(
                if (isPlaying) R.drawable.glyph_play else android.R.drawable.ic_media_pause
            )
        }

        // Execute lookup pass across collected targets asynchronously
        var coverDrawable: Drawable? = null
        LoadCoverArt(track.filePath, imageViewsToUpdate)
        when(selectedNowPlayingLayout) {
            "classic" -> {

            }
            "disc" -> {
                // 1. Extract raw bytes from the audio track file container metadata
                val retriever = android.media.MediaMetadataRetriever()
                val rawArtBytes = try {
                    retriever.setDataSource(track.filePath)
                    retriever.embeddedPicture
                } catch (e: Exception) {
                    null
                } finally {
                    retriever.release()
                }

                // 2. Convert bytes to a Drawable, or fall back to your default placeholder asset
                val rawDrawable = if (rawArtBytes != null) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawArtBytes, 0, rawArtBytes.size)
                    android.graphics.drawable.BitmapDrawable(resources, bitmap)
                } else {
                    // Fallback resource if the audio file lacks embedded album artwork tags
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.album_cover)
                }

                // Pass it safely down to the jacket effect layer compiler block
                rawDrawable?.let {
                    npCDapplyJacketOverlayEffect(findViewById(R.id.npCDCoverImageView), it)
                }

                updateCDDrawable(track)
            }
        }


        nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)?.let { viewGroup ->
            viewGroup.findViewById<TextView>(R.id.textView)?.text = track.title
            viewGroup.findViewById<TextView>(R.id.textView2)?.text = track.artist
        }

        val totalDuration = musicService?.mediaPlayer?.duration ?: 0
        npPlaybackSeekBar.max = totalDuration
        npTextTimeTotal.text = formatTimeString(totalDuration)

        nowPlayingOverlayContainer.findViewById<FrameLayout>(R.id.npEmptyViewport)?.findViewById<ImageView>(R.id.rowIcon)?.setImageResource(R.drawable.cover)
    }

    private fun togglePlayback() {
        val service = musicService ?: return
        val isPlaying = service.togglePlayback()
        updatePlaybackUiStates(isPlaying)
        if (isPlaying) {
            seekHandler.post(progressUpdater)
            startCDPlaybackSpinning()
        } else {
            //seekHandler.removeCallbacks(progressUpdater)
            stopCDPlaybackSpinning()
        }
    }

    private fun stopPlayback() {
        musicService?.stopPlayback()
        npPlaybackSeekBar.progress = 0
        npTextTimeElapsed.text = "00:00"
        updatePlaybackUiStates(isPlaying = false)
        seekHandler.removeCallbacks(progressUpdater)

        stopCDPlaybackSpinning()
        Toast.makeText(this, "Playback stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updatePlaybackUiStates(isPlaying: Boolean) {
        val playResource = if (isPlaying) R.drawable.button_pause else R.drawable.button_play
        btnMediaPlay.setImageResource(playResource)
        npBtnPlayPause.setImageResource(playResource)
    }

    private fun formatTimeString(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        seekHandler.removeCallbacks(progressUpdater)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun updateBreadcrumbs(primaryNode: String, secondaryNode: String?) {
        tvBreadcrumbHome.text = primaryNode
        if (secondaryNode != null) {
            ivBreadcrumbSeparator.visibility = View.VISIBLE
            ivBreadcrumbArtists.visibility = View.VISIBLE
            tvBreadcrumbArtists.visibility = View.VISIBLE
            tvBreadcrumbArtists.text = secondaryNode
        } else {
            ivBreadcrumbSeparator.visibility = View.GONE
            ivBreadcrumbArtists.visibility = View.GONE
            tvBreadcrumbArtists.visibility = View.GONE
        }
    }

    companion object {
        private const val TYPE_CATEGORY = 0
        private const val TYPE_ARTIST = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_SONG = 3
    }

    inner class UniversalLibraryAdapter(
        private val items: List<Any>,
        private val viewType: Int,
        private val onItemClicked: (Any) -> Unit
    ) : RecyclerView.Adapter<UniversalLibraryAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val mainText: TextView = v.findViewById(R.id.rowMainText)
            val subText: TextView = v.findViewById(R.id.rowSubText)
            val duration: TextView = v.findViewById(R.id.rowDuration)
            init {
                v.setOnClickListener { onItemClicked(items[adapterPosition]) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_library_row, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val currentItem = items[position]
            holder.subText.visibility = View.VISIBLE
            holder.duration.visibility = View.VISIBLE

            when (viewType) {
                TYPE_CATEGORY -> {
                    val label = currentItem as String
                    holder.mainText.text = label
                    holder.subText.visibility = View.GONE
                    holder.duration.visibility = View.GONE
                    holder.icon.setImageResource(when (label) {
                        "Artists" -> R.drawable.t_artist
                        "Albums" -> R.drawable.t_album
                        "Playlists" -> R.drawable.t_playlist
                        else -> R.drawable.t_genre
                    })
                }
                TYPE_ARTIST -> {
                    holder.mainText.text = currentItem as String
                    holder.subText.text = "View Artist Collection"
                    holder.duration.visibility = View.GONE
                    holder.icon.setImageResource(R.drawable.t_artist)
                }
                TYPE_ALBUM -> {
                    val album = currentItem as AudioAlbum
                    holder.mainText.text = album.name
                    holder.subText.text = "${album.albumArtist} • ${album.date}"
                    holder.duration.visibility = View.GONE
                    holder.icon.setImageResource(R.drawable.t_album)
                }
                TYPE_SONG -> {
                    val song = currentItem as AudioTrack
                    holder.mainText.text = "${song.track_number}. ${song.title}"
                    holder.subText.text = song.artist
                    holder.duration.text = String.format("%02d:%02d", song.length / 60, song.length % 60)
                    holder.icon.setImageResource(R.drawable.t_genre)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}