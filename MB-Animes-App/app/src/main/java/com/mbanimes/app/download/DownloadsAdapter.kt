package com.mbanimes.app.download

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mbanimes.app.R
import com.mbanimes.app.databinding.ItemDownloadBinding

class DownloadsAdapter(
    private val onPrimary: (DownloadItem) -> Unit,
    private val onDelete: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadsAdapter.VH>(Diff) {

    class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
        override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = getItem(position)
        val ctx = h.b.root.context
        h.b.title.text = d.title
        h.b.episode.text = d.episode
        if (d.poster.isNotEmpty()) h.b.poster.load(d.poster) else h.b.poster.setImageDrawable(null)

        h.b.progress.visibility = if (d.state == DlState.DONE) View.GONE else View.VISIBLE
        h.b.progress.setProgressCompat(d.progress, false)

        h.b.status.text = when (d.state) {
            DlState.QUEUED -> ctx.getString(R.string.state_queued)
            DlState.DOWNLOADING -> ctx.getString(R.string.state_downloading, d.progress)
            DlState.PAUSED ->
                if (d.waitingWifi) ctx.getString(R.string.state_wifi_wait)
                else ctx.getString(R.string.state_paused, d.progress)
            DlState.DONE -> ctx.getString(
                if (d.uri != null) R.string.state_done_public else R.string.state_done,
                Formatter.formatShortFileSize(ctx, d.bytes)
            )
            DlState.FAILED -> ctx.getString(R.string.state_failed, d.error ?: "erro")
        }

        h.b.status.setTextColor(
            ContextCompat.getColor(
                ctx,
                when (d.state) {
                    DlState.DONE -> R.color.success
                    DlState.FAILED -> R.color.danger
                    else -> R.color.muted
                }
            )
        )

        val active = d.state == DlState.DOWNLOADING || d.state == DlState.QUEUED
        h.b.primary.setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        h.b.primary.contentDescription = ctx.getString(
            when {
                active -> R.string.action_pause
                d.state == DlState.DONE -> R.string.action_play
                else -> R.string.action_resume
            }
        )
        h.b.primary.setOnClickListener { onPrimary(d) }
        h.b.delete.setOnClickListener { onDelete(d) }
        h.b.root.setOnClickListener { if (d.state == DlState.DONE) onPrimary(d) }
    }
}
