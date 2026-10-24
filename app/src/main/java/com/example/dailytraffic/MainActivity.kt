package com.example.dailytraffic

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import com.example.dailytraffic.data.AndroidNetworkStatsDataSource
import com.example.dailytraffic.data.DailyTrafficRepository
import com.example.dailytraffic.data.PackageResolver
import com.example.dailytraffic.databinding.ActivityMainBinding
import com.example.dailytraffic.domain.DailyTraffic
import com.example.dailytraffic.domain.ErrorKind
import com.example.dailytraffic.domain.LoadState
import com.example.dailytraffic.domain.NetworkFilter
import com.example.dailytraffic.domain.WeeklyUsage
import com.example.dailytraffic.permissions.UsageAccessChecker
import com.example.dailytraffic.time.DayRangeProvider
import com.example.dailytraffic.ui.TrafficListAdapter
import com.example.dailytraffic.ui.TrafficListItem
import com.example.dailytraffic.ui.TrafficListState
import com.example.dailytraffic.ui.TrendState
import com.example.dailytraffic.util.TrafficFormatter
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var accessChecker: UsageAccessChecker
    private lateinit var repository: DailyTrafficRepository
    private lateinit var adapter: TrafficListAdapter
    private lateinit var executor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dayRangeProvider = DayRangeProvider()
    private val trendCache = HashMap<TrendKey, WeeklyUsage>()

    private var selectedDayStart = 0L
    private var followToday = true
    private var networkFilter = NetworkFilter.ALL
    private var currentTraffic: DailyTraffic? = null
    private var otherExpanded = false
    private var expandedUid: Int? = null
    private var trendState: TrendState = TrendState.None
    private var requestId = 0
    private var trendRequestId = 0
    private var isLoading = false
    private var destroyed = false
    private var loadTask: Future<*>? = null
    private var trendTask: Future<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        accessChecker = UsageAccessChecker(this)
        repository = DailyTrafficRepository(
            AndroidNetworkStatsDataSource(this),
            PackageResolver(this),
            dayRangeProvider
        )
        adapter = TrafficListAdapter()
        executor = Executors.newSingleThreadExecutor()

        val today = dayRangeProvider.startOfDay(dayRangeProvider.now())
        selectedDayStart = savedInstanceState?.getLong(KEY_SELECTED_DAY, today) ?: today
        followToday = savedInstanceState?.getBoolean(KEY_FOLLOW_TODAY, true) ?: true
        networkFilter = savedInstanceState?.getString(KEY_NETWORK_FILTER)
            ?.let { saved -> NetworkFilter.entries.firstOrNull { it.name == saved } }
            ?: NetworkFilter.ALL
        if (followToday) selectedDayStart = today

        binding.appList.adapter = adapter
        installListeners()
        updateDateUi()
        updateFilterUi()
        showLoading()
    }

    override fun onResume() {
        super.onResume()
        if (destroyed) return
        if (followToday) {
            selectedDayStart = dayRangeProvider.startOfDay(dayRangeProvider.now())
        }
        updateDateUi()

        if (!accessChecker.hasUsageAccess()) {
            invalidateLoad()
            showPermission(getString(R.string.permission_explanation))
        } else if (!isLoading) {
            loadSelectedDay()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_SELECTED_DAY, selectedDayStart)
        outState.putBoolean(KEY_FOLLOW_TODAY, followToday)
        outState.putString(KEY_NETWORK_FILTER, networkFilter.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        invalidateLoad()
        resetExpansion(false)
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun installListeners() {
        binding.previousDay.setOnClickListener {
            selectedDayStart = dayRangeProvider.addDays(selectedDayStart, -1)
            followToday = false
            updateDateUi()
            loadSelectedDay()
        }
        binding.nextDay.setOnClickListener {
            val today = dayRangeProvider.startOfDay(dayRangeProvider.now())
            if (selectedDayStart >= today) return@setOnClickListener
            selectedDayStart = dayRangeProvider.addDays(selectedDayStart, 1)
            followToday = selectedDayStart == today
            updateDateUi()
            loadSelectedDay()
        }
        binding.date.setOnClickListener {
            showDatePicker()
        }
        binding.refresh.setOnClickListener {
            if (!isLoading) loadSelectedDay()
        }
        binding.permissionAction.setOnClickListener {
            openUsageAccessSettings()
        }
        binding.filterIcon.setOnClickListener {
            showNetworkFilterDialog()
        }
        binding.helpIcon.setOnClickListener {
            showHelpDialog()
        }
        binding.appList.setOnItemClickListener { _, _, position, _ ->
            handleListItemClick(adapter.getItem(position))
        }
    }

    private fun handleListItemClick(item: TrafficListItem) {
        when (item) {
            is TrafficListItem.AppRow -> {
                if (item.row.canShowTrend) toggleTrend(item.row.uid)
            }

            is TrafficListItem.SharedGroupRow -> toggleTrend(item.group.uid)
            is TrafficListItem.OtherRow -> {
                otherExpanded = !otherExpanded
                if (!otherExpanded) clearExpandedTrend()
                renderList()
            }
        }
    }

    private fun toggleTrend(uid: Int) {
        if (expandedUid == uid) {
            clearExpandedTrend()
            renderList()
            return
        }

        expandedUid = uid
        val key = TrendKey(uid, selectedDayStart, networkFilter)
        val cached = trendCache[key]
        if (cached != null) {
            trendState = TrendState.Success(cached)
            renderList()
            return
        }

        trendState = TrendState.Loading
        renderList()
        loadTrend(uid, key)
    }

    private fun loadTrend(uid: Int, key: TrendKey) {
        val currentRequest = ++trendRequestId
        trendTask?.cancel(true)
        trendTask = executor.submit {
            val result = repository.loadWeekly(uid, key.endDayStart, key.filter)
            mainHandler.post {
                if (destroyed || currentRequest != trendRequestId || expandedUid != uid) {
                    return@post
                }
                trendTask = null
                when (result) {
                    LoadState.Loading -> trendState = TrendState.Loading
                    LoadState.PermissionDenied -> {
                        expandedUid = null
                        trendState = TrendState.None
                        showPermission(getString(R.string.weekly_permission))
                        return@post
                    }

                    is LoadState.Failure -> {
                        trendState = TrendState.Failure(getString(R.string.weekly_failed))
                    }

                    is LoadState.Success -> {
                        trendCache[key] = result.value
                        trendState = TrendState.Success(result.value)
                    }
                }
                renderList()
            }
        }
    }

    private fun clearExpandedTrend() {
        expandedUid = null
        trendState = TrendState.None
        trendRequestId++
        trendTask?.cancel(true)
        trendTask = null
    }

    private fun resetExpansion(clearCache: Boolean) {
        otherExpanded = false
        clearExpandedTrend()
        if (clearCache) trendCache.clear()
    }

    private fun showDatePicker() {
        val calendar = dayRangeProvider.calendarFor(selectedDayStart)
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedDayStart = dayRangeProvider.startOfDay(
                    dayRangeProvider.millisForDate(year, month, dayOfMonth)
                )
                followToday = dayRangeProvider.isToday(selectedDayStart)
                updateDateUi()
                loadSelectedDay()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = dayRangeProvider.now()
        dialog.show()
    }

    private fun showNetworkFilterDialog() {
        val labels = arrayOf(
            getString(R.string.filter_all),
            getString(R.string.filter_mobile),
            getString(R.string.filter_wifi)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_title)
            .setSingleChoiceItems(labels, networkFilter.ordinal) { dialog, which ->
                dialog.dismiss()
                val chosen = NetworkFilter.entries[which]
                if (chosen != networkFilter) {
                    networkFilter = chosen
                    resetExpansion(true)
                    updateFilterUi()
                    if (currentTraffic != null) {
                        renderList()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun updateDateUi() {
        binding.date.text = dayRangeProvider.formatDay(selectedDayStart)
        val isToday = dayRangeProvider.isToday(selectedDayStart)
        binding.nextDay.isEnabled = !isToday
        binding.nextDay.alpha = if (isToday) 0.35f else 1f
    }

    private fun updateFilterUi() {
        val label = when (networkFilter) {
            NetworkFilter.ALL -> getString(R.string.filter_all)
            NetworkFilter.MOBILE -> getString(R.string.filter_mobile)
            NetworkFilter.WIFI -> getString(R.string.filter_wifi)
        }
        binding.deviceTotalLabel.text = getString(R.string.device_total_filtered, label)
        val tint = if (networkFilter == NetworkFilter.ALL) {
            getColor(R.color.secondary_text)
        } else {
            getColor(R.color.accent)
        }
        binding.filterIcon.imageTintList = ColorStateList.valueOf(tint)
        currentTraffic?.let {
            binding.total.text = TrafficFormatter.format(it.deviceBytes(networkFilter).total)
        }
    }

    private fun loadSelectedDay() {
        if (destroyed) return
        if (!accessChecker.hasUsageAccess()) {
            invalidateLoad()
            showPermission(getString(R.string.permission_explanation))
            return
        }

        val range = dayRangeProvider.rangeFor(selectedDayStart)
        if (range == null) {
            invalidateLoad()
            showFailure(ErrorKind.INVALID_RANGE)
            return
        }

        resetExpansion(true)
        currentTraffic = null
        val currentRequest = ++requestId
        loadTask?.cancel(true)
        isLoading = true
        showLoading()
        loadTask = executor.submit {
            val result = repository.load(range)
            mainHandler.post {
                if (destroyed || currentRequest != requestId) return@post
                loadTask = null
                isLoading = false
                render(result)
            }
        }
    }

    private fun render(state: LoadState<DailyTraffic>) {
        when (state) {
            LoadState.Loading -> showLoading()
            LoadState.PermissionDenied -> showPermission(getString(R.string.permission_lost))
            is LoadState.Failure -> showFailure(state.kind)
            is LoadState.Success -> showTraffic(state.value)
        }
    }

    private fun showTraffic(traffic: DailyTraffic) {
        finishLoading()
        currentTraffic = traffic
        binding.permissionAction.visibility = View.GONE
        updateFilterUi()
        renderList()
    }

    private fun renderList() {
        val traffic = currentTraffic ?: return
        val listWasVisible = binding.appList.visibility == View.VISIBLE
        val firstVisible = binding.appList.firstVisiblePosition
        val firstOffset = binding.appList.getChildAt(0)?.top ?: 0
        adapter.submit(
            TrafficListState(
                traffic = traffic,
                filter = networkFilter,
                otherExpanded = otherExpanded,
                expandedUid = expandedUid,
                trend = trendState
            )
        )
        binding.total.text = TrafficFormatter.format(traffic.deviceBytes(networkFilter).total)
        if (adapter.count == 0) {
            binding.appList.visibility = View.GONE
            binding.progress.visibility = View.GONE
            binding.stateText.text = if (networkFilter == NetworkFilter.ALL) {
                getString(R.string.no_data)
            } else {
                getString(R.string.no_data_network)
            }
            binding.permissionAction.visibility = View.GONE
            binding.statePanel.visibility = View.VISIBLE
        } else {
            binding.appList.visibility = View.VISIBLE
            binding.statePanel.visibility = View.GONE
            if (listWasVisible) {
                binding.appList.post {
                    if (!destroyed) {
                        binding.appList.setSelectionFromTop(firstVisible, firstOffset)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        binding.refresh.isEnabled = false
        binding.refresh.alpha = 0.35f
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.clear()
        binding.permissionAction.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.stateText.text = getString(R.string.loading)
        binding.statePanel.visibility = View.VISIBLE
    }

    private fun showPermission(message: String) {
        finishLoading()
        currentTraffic = null
        resetExpansion(true)
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.clear()
        binding.progress.visibility = View.GONE
        binding.stateText.text = message
        binding.permissionAction.visibility = View.VISIBLE
        binding.statePanel.visibility = View.VISIBLE
    }

    private fun showFailure(kind: ErrorKind) {
        if (kind == ErrorKind.SECURITY) {
            showPermission(getString(R.string.permission_lost))
            return
        }
        finishLoading()
        currentTraffic = null
        resetExpansion(true)
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.clear()
        binding.progress.visibility = View.GONE
        binding.stateText.text = when (kind) {
            ErrorKind.REMOTE_SERVICE -> getString(R.string.remote_unavailable)
            ErrorKind.INVALID_RANGE -> getString(R.string.invalid_date)
            ErrorKind.SECURITY -> getString(R.string.permission_lost)
            ErrorKind.UNKNOWN -> getString(R.string.read_failed)
        }
        binding.permissionAction.visibility = View.GONE
        binding.statePanel.visibility = View.VISIBLE
    }

    private fun finishLoading() {
        isLoading = false
        binding.refresh.isEnabled = true
        binding.refresh.alpha = 1f
    }

    private fun invalidateLoad() {
        requestId++
        loadTask?.cancel(true)
        loadTask = null
        isLoading = false
    }

    private fun openUsageAccessSettings() {
        val primary = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            startActivity(primary)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to application details.
        } catch (_: RuntimeException) {
            // Fall through to application details.
        }

        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            showPermission(getString(R.string.open_settings_failed))
        } catch (_: RuntimeException) {
            showPermission(getString(R.string.open_settings_failed))
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    private data class TrendKey(
        val uid: Int,
        val endDayStart: Long,
        val filter: NetworkFilter
    )

    private companion object {
        const val KEY_SELECTED_DAY = "selected_day"
        const val KEY_FOLLOW_TODAY = "follow_today"
        const val KEY_NETWORK_FILTER = "network_filter"
    }
}
