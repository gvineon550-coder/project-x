package com.tvplayer.app
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
class ChannelAdapter(private val items: List<Channel>, private val onClick: (Channel) -> Unit, private val onFavoriteClick: (Channel) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtNumber: TextView = v.findViewById(R.id.txtNumber)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val btnFav: ImageView = v.findViewById(R.id.btnFav)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH = VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        h.txtNumber.text = (pos + 1).toString()
        h.name.text = ch.name
        if (ch.logo.isNotBlank()) {
            h.logo.load(ch.logo) { 
                crossfade(true)
                size(160, 160)
                placeholder(android.R.color.darker_gray)
                error(android.R.color.darker_gray)
            }
        } else {
            h.logo.load(null)
            h.logo.setImageDrawable(null)
        }
        if (ch.isFavorite) { h.btnFav.setImageResource(android.R.drawable.btn_star_big_on); h.btnFav.setColorFilter(android.graphics.Color.YELLOW) } else { h.btnFav.setImageResource(android.R.drawable.btn_star_big_off); h.btnFav.setColorFilter(android.graphics.Color.GRAY) }
        h.itemView.setOnClickListener { onClick(ch) }
        h.btnFav.setOnClickListener { onFavoriteClick(ch) }
    }
    override fun getItemCount() = items.size
}
