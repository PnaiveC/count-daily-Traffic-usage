package com.example.dailytraffic.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.example.dailytraffic.R
import com.example.dailytraffic.databinding.ItemAppTrafficBinding
import com.example.dailytraffic.domain.NetworkFilter
import com.example.dailytraffic.domain.TrafficAggregator
import com.example.dailytraffic.domain.WeeklyUsage
import com.example.dailytraffic.util.TrafficFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrafficListAdapter : BaseAdapter() {
    private var state: TrafficListState? = null
    private var items: List<TrafficListItem> = emptyList()
    private val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun submit(newState: TrafficListState) {
        state = newState
        items = buildItems(newState)
        notifyDataSetChanged()
    }

    fun clear() {
        state = null
        items = emptyList()
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun hasStableIds(): Boolean = true

    override fun getItem(position: Int): TrafficListItem = items[position]

    override fun getItemId(position: Int): Long = when (val item = items[position]) {
        is TrafficListItem.AppRow -> item.row.uid.toLong()
        is TrafficListItem.SharedGroupRow -> item.group.uid.toLong()
        is TrafficListItem.OtherRow -> Long.MIN_VALUE
    }

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

        bind(holder.binding, items[position])
        return view
    }

    private fun bind(binding: ItemAppTrafficBinding, item: TrafficListItem) {
        val current = state ?: return
        val context = binding.root.context
        binding.details.removeAllViews()
        binding.details.visibility = View.GONE
        binding.icon.setImageDrawable(context.getDrawable(android.R.drawable.sym_def_app_icon))
        binding.subtitle.visibility = View.GONE
        binding.expandIndicator.visibility = View.GONE

        when (item) {
            is TrafficListItem.AppRow -> {
                val row = item.row
                binding.icon.setImageDrawable(
                    row.icon ?: context.getDrawable(android.R.drawable.sym_def_app_icon)
                )
                binding.label.text = row.label
                binding.bytes.text = TrafficFormatter.format(
                    row.bytesFor(current.filter).total
                )
                if (row.canShowTrend) {
                    val expanded = current.expandedUid == row.uid
                    binding.expandIndicator.visibility = View.VISIBLE
                    binding.expandIndicator.text = expandSymbol(expanded)
                    if (expanded) {
                        showTrendPanel(binding, current, row.uid, null)
                    }
                }
            }

            is TrafficListItem.OtherRow -> {
                binding.label.text = context.getString(R.string.other_title)
                binding.subtitle.visibility = View.VISIBLE
                binding.subtitle.text = context.getString(
                    R.string.other_subtitle,
                    item.groupCount,
                    item.packageCount
                )
                binding.bytes.text = TrafficFormatter.format(item.totalBytes)
                binding.expandIndicator.visibility = View.VISIBLE
                binding.expandIndicator.text = expandSymbol(current.otherExpanded)
            }

            is TrafficListItem.SharedGroupRow -> {
                val group = item.group
                val packageCount = group.packageNames.size
                binding.label.text = context.getString(R.string.shared_uid_title, packageCount)
                binding.subtitle.visibility = View.VISIBLE
                binding.subtitle.text = context.getString(
                    R.string.shared_uid_package_preview,
                    group.packageNames.take(2).joinToString(", "),
                    if (packageCount > 2) "…" else ""
                )
                binding.bytes.text = TrafficFormatter.format(
                    group.bytesFor(current.filter).total
                )
                val expanded = current.expandedUid == group.uid
                binding.expandIndicator.visibility = View.VISIBLE
                binding.expandIndicator.text = expandSymbol(expanded)
                if (expanded) {
                    showTrendPanel(binding, current, group.uid, group.packageNames)
                }
            }
        }
    }

    private fun showTrendPanel(
        binding: ItemAppTrafficBinding,
        state: TrafficListState,
        uid: Int,
        sharedPackageNames: List<String>?
    ) {
        binding.details.visibility = View.VISIBLE
        val context = binding.root.context

        if (!sharedPackageNames.isNullOrEmpty()) {
            binding.details.addView(
                textView(
                    context,
                    context.getString(
                        R.string.shared_packages,
                        sharedPackageNames.joinToString("\n") { "• $it" }
                    ),
                    null,
                    13f,
                    R.color.secondary_text
                ).apply {
                    setPadding(0, 0, 0, dp(context, 8))
                }
            )
        }

        binding.details.addView(
            textView(
                context,
                context.getString(R.string.recent_seven_days),
                null,
                13f,
                R.color.secondary_text
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(context, 6))
            }
        )

        when (val trend = state.trend) {
            TrendState.None -> addMessage(binding.details, context.getString(R.string.weekly_empty))
            TrendState.Loading -> addMessage(binding.details, context.getString(R.string.weekly_loading))
            is TrendState.Failure -> addMessage(binding.details, trend.message)
            is TrendState.Success -> {
                if (trend.usage.uid == uid) {
                    addWeeklyRows(binding.details, context, trend.usage)
                } else {
                    addMessage(binding.details, context.getString(R.string.weekly_empty))
                }
            }
        }
    }

    private fun addWeeklyRows(container: LinearLayout, context: Context, usage: WeeklyUsage) {
        val maxBytes = usage.points.mapNotNull { it.bytes?.total }.maxOrNull() ?: 0L
        for (point in usage.points) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(
                TextView(context).apply {
                    text = dayFormat.format(Date(point.dayStartMillis))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(context.getColor(R.color.secondary_text))
                    layoutParams = LinearLayout.LayoutParams(
                        dp(context, 50),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )

            val bytes = point.bytes
            val fraction = if (bytes == null || maxBytes <= 0L) {
                0f
            } else {
                bytes.total.toDouble().div(maxBytes.toDouble()).toFloat()
            }
            row.addView(
                UsageBarView(context).apply {
                    this.fraction = fraction
                    layoutParams = LinearLayout.LayoutParams(0, dp(context, 6), 1f).apply {
                        marginStart = dp(context, 8)
                        marginEnd = dp(context, 8)
                    }
                }
            )

            row.addView(
                TextView(context).apply {
                    text = bytes?.let { TrafficFormatter.format(it.total) }
                        ?: context.getString(R.string.unavailable_value)
                    gravity = Gravity.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(context.getColor(R.color.secondary_text))
                    layoutParams = LinearLayout.LayoutParams(
                        dp(context, 72),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4)
                }
            )
        }
    }

    private fun addMessage(container: LinearLayout, message: String) {
        val context = container.context
        container.addView(
            textView(context, message, null, 13f, R.color.secondary_text)
        )
    }

    private fun textView(
        context: Context,
        textValue: String,
        ellipsizeAt: TextUtils.TruncateAt?,
        textSizeSp: Float,
        colorRes: Int
    ) = TextView(context).apply {
        text = textValue
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setTextColor(context.getColor(colorRes))
        ellipsizeAt?.let {
            maxLines = 1
            ellipsize = it
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildItems(state: TrafficListState): List<TrafficListItem> {
        val apps = TrafficAggregator.sortRows(state.traffic.appRows, state.filter)
        val groups = TrafficAggregator.sortSharedGroups(state.traffic.sharedGroups, state.filter)
        val result = ArrayList<TrafficListItem>(apps.size + groups.size + 1)
        apps.mapTo(result) { TrafficListItem.AppRow(it) }

        if (groups.isNotEmpty()) {
            result += TrafficListItem.OtherRow(
                totalBytes = groups.sumOf { it.bytesFor(state.filter).total },
                groupCount = groups.size,
                packageCount = groups.sumOf { it.packageNames.size }
            )
            if (state.otherExpanded) {
                groups.mapTo(result) { TrafficListItem.SharedGroupRow(it) }
            }
        }
        return result
    }

    private fun expandSymbol(expanded: Boolean): String =
        if (expanded) "▾" else "▸"

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class ViewHolder(val binding: ItemAppTrafficBinding)
}
