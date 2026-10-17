package com.example.dailytraffic

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.example.dailytraffic.permissions.UsageAccessChecker
import com.example.dailytraffic.time.DayRangeProvider
import com.example.dailytraffic.ui.TrafficListAdapter
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
    private var selectedDayStart = 0L
    private var followToday = true
    private var requestId = 0
    private var isLoading = false
    private var destroyed = false
    private var loadTask: Future<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        accessChecker = UsageAccessChecker(this)
        repository = DailyTrafficRepository(
            AndroidNetworkStatsDataSource(this),
            PackageResolver(this)
        )
        adapter = TrafficListAdapter()
        executor = Executors.newSingleThreadExecutor()

        val today = dayRangeProvider.startOfDay(dayRangeProvider.now())
        selectedDayStart = savedInstanceState?.getLong(KEY_SELECTED_DAY, today) ?: today
        followToday = savedInstanceState?.getBoolean(KEY_FOLLOW_TODAY, true) ?: true
        if (followToday) selectedDayStart = today

        binding.appList.adapter = adapter
        installListeners()
        updateDateUi()
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        invalidateLoad()
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

    private fun updateDateUi() {
        binding.date.text = dayRangeProvider.formatDay(selectedDayStart)
        val isToday = dayRangeProvider.isToday(selectedDayStart)
        binding.nextDay.isEnabled = !isToday
        binding.nextDay.alpha = if (isToday) 0.35f else 1f
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
            showFailure(ErrorKind.INVALID_RANGE)
            return
        }

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
        binding.total.text = TrafficFormatter.format(traffic.deviceTotal)
        binding.permissionAction.visibility = View.GONE
        if (traffic.rows.isEmpty()) {
            binding.appList.visibility = View.GONE
            adapter.items = emptyList()
            binding.progress.visibility = View.GONE
            binding.stateText.text = getString(R.string.no_data)
            binding.statePanel.visibility = View.VISIBLE
        } else {
            adapter.items = traffic.rows
            binding.appList.visibility = View.VISIBLE
            binding.statePanel.visibility = View.GONE
        }
    }

    private fun showLoading() {
        binding.refresh.isEnabled = false
        binding.refresh.alpha = 0.35f
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.items = emptyList()
        binding.permissionAction.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.stateText.text = getString(R.string.loading)
        binding.statePanel.visibility = View.VISIBLE
    }

    private fun showPermission(message: String) {
        finishLoading()
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.items = emptyList()
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
        binding.total.text = getString(R.string.value_placeholder)
        binding.appList.visibility = View.GONE
        adapter.items = emptyList()
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

    private companion object {
        const val KEY_SELECTED_DAY = "selected_day"
        const val KEY_FOLLOW_TODAY = "follow_today"
    }
}
