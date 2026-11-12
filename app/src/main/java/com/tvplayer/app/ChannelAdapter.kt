package com.tvplayer.app
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit)
    : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        h.name.text = ch.name
        if (ch.logo.isNotBlank()) {
            h.logo.load(ch.logo) {
                crossfade(true)
                placeholder(android.R.color.darker_gray)
                error(android.R.color.darker_gray)
            }
        } else {
            h.logo.setImageResource(android.R.color.darker_gray)
        }
        h.itemView.setOnClickListener { onClick(ch) }
    }
    override fun getItemCount() = items.size
}
