package com.ultra.sniffer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ultra.sniffer.R
import com.ultra.sniffer.core.DownloadLog
import com.ultra.sniffer.core.MediaSource
import com.ultra.sniffer.core.SniffedMedia
import com.ultra.sniffer.databinding.ItemMediaBinding

/** List of detected media with live download status. */
class MediaAdapter(
    private val onClick: (SniffedMedia) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    private var items: List<SniffedMedia> = emptyList()
    private var logs: Map<String, DownloadLog.Entry> = emptyMap()

    fun submit(media: List<SniffedMedia>, log: Map<String, DownloadLog.Entry>) {
        items = media
        logs = log
        notifyDataSetChanged()
    }

    class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val media = items[position]
        val b = holder.binding

        b.tvTitle.text = media.label
        val sourceLabel = if (media.downloadable) media.source.label else "host hint"
        b.tvSubtitle.text = "${media.host} • $sourceLabel"
        b.tvChip.text = media.kind.label

        val entry = logs[media.url]
        when {
            entry == null -> {
                b.tvStatus.visibility = android.view.View.GONE
            }
            entry.phase == DownloadLog.Phase.DONE -> {
                b.tvStatus.visibility = android.view.View.VISIBLE
                b.tvStatus.text = "✅ ${entry.message}"
                b.tvStatus.setTextColor(b.root.context.getColor(R.color.status_ok))
            }
            entry.phase == DownloadLog.Phase.ERROR -> {
                b.tvStatus.visibility = android.view.View.VISIBLE
                b.tvStatus.text = "❌ ${entry.message}"
                b.tvStatus.setTextColor(b.root.context.getColor(R.color.status_error))
            }
            else -> {
                b.tvStatus.visibility = android.view.View.VISIBLE
                val pct = if (entry.percent > 0) "${entry.percent}% • " else ""
                b.tvStatus.text = "$pct${entry.message}"
                b.tvStatus.setTextColor(b.root.context.getColor(R.color.status_info))
            }
        }

        b.ivIcon.setImageResource(
            when {
                !media.downloadable -> R.drawable.ic_stat_sniffer
                media.kind.label.startsWith("HLS") || media.kind.label.startsWith("DASH") -> R.drawable.ic_download
                else -> R.drawable.ic_download
            }
        )

        b.root.setOnClickListener { onClick(media) }
    }

    override fun getItemCount(): Int = items.size
}
