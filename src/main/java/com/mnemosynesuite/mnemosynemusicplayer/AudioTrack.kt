package com.mnemosynesuite.mnemosynemusicplayer

data class AudioTrack(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val track_number: Int, // Sometimes track numbers are strings in mp3 files like 01/10, which must be converted to 1 anyway
    val genre: String,
    val date: String,
    val filePath: String,
    val length: Int // in seconds
)