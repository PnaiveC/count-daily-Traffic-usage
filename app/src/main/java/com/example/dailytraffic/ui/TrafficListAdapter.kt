package com.example.dailytraffic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.example.dailytraffic.databinding.ItemAppTrafficBinding
import com.example.dailytraffic.domain.TrafficRow
import com.example.dailytraffic.util.TrafficFormatter

class TrafficListAdapter : BaseAdapter() {
    var items: List<TrafficRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): TrafficRow = items[position]

    override fun getItemId(position: Int): Long = items[position].uid.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: ViewHolder
        val view: View
        if (convertView == null) {
            val binding = ItemAppTrafficBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            holder = ViewHolder(binding)
            view = binding.root
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        val item = getItem(position)
        holder.binding.icon.setImageDrawable(
            item.icon ?: parent.context.getDrawable(android.R.drawable.sym_def_app_icon)
        )
        holder.binding.label.text = item.label
        holder.binding.bytes.text = TrafficFormatter.format(item.bytes.total)
        return view
    }

    private class ViewHolder(val binding: ItemAppTrafficBinding)
}
