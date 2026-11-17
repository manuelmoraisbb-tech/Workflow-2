package com.mbanimes.app.download

enum class DlState { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val id: String,
    val title: String,
    val episode: String,
    val poster: String,
    val url: String,
    val pageUrl: String,
    val createdAt: Long,
    val state: DlState = DlState.QUEUED,
    val progress: Int = 0,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val filePath: String? = null,
    val uri: String? = null,
    val error: String? = null,
    val waitingWifi: Boolean = false
)
