package com.example.vidcollage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vidcollage.collage.CollageStore
import com.example.vidcollage.databinding.ActivityMainBinding
import com.example.vidcollage.pipeline.ProcessingState
import com.example.vidcollage.pipeline.VideoResult
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CollageViewModel by viewModels()

    private val adapter = ResultsAdapter(
        onSave = { result -> saveWithPermission(result) },
        onShare = { result -> share(result) },
    )

    /** Set while we wait for the legacy storage permission on API 28 and below. */
    private var pendingSave: VideoResult? = null

    /** Kept so the stage stepper only re-animates when the stage actually moves on. */
    private var lastStageOrdinal = -1

    // ACTION_OPEN_DOCUMENT rather than the photo picker: the photo picker hands back synthetic
    // uris whose display name is just the media id, and the collage is titled with the file name.
    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        viewModel.process(uris.take(MAX_VIDEOS))
    }

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val result = pendingSave
        pendingSave = null
        when {
            result == null -> Unit
            granted -> save(result)
            else -> snack(getString(R.string.storage_permission_needed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The hero gradient is dark in both themes, so the status bar icons stay light.
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        applyInsets()
        setUpResults()
        setUpEmptyState()

        binding.pickButton.addPressBounce()
        binding.pickButton.setOnClickListener {
            it.tick()
            pickVideos.launch(arrayOf("video/*"))
        }
        binding.cancelButton.setOnClickListener {
            it.tick()
            viewModel.cancel()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    /**
     * The hero paints the gradient behind the status bar, so it takes the top inset itself and the
     * scrolling content below takes the bottom one.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val sidePadding = dp(20)
            binding.hero.updatePadding(
                left = bars.left + sidePadding,
                top = bars.top + dp(20),
                right = bars.right + sidePadding,
            )
            binding.resultsList.updatePadding(bottom = bars.bottom + dp(24))
            binding.emptyState.updatePadding(bottom = bars.bottom + dp(24))
            insets
        }
    }

    private fun setUpResults() {
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter
        binding.resultsList.itemAnimator = DefaultItemAnimator().apply { addDuration = 260 }
        binding.resultsList.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(this, R.anim.layout_animation_slide_up)
    }

    private fun setUpEmptyState() {
        val steps = listOf(
            binding.step1 to getString(R.string.step_one, MAX_VIDEOS),
            binding.step2 to getString(R.string.step_two),
            binding.step3 to getString(R.string.step_three),
        )
        steps.forEachIndexed { index, (row, text) ->
            row.stepNumber.text = (index + 1).toString()
            row.stepText.text = text
        }
    }

    private fun render(state: ProcessingState) {
        when (state) {
            ProcessingState.Idle -> {
                binding.progressGroup.fadeTo(false)
                binding.pickButton.isEnabled = true
                binding.emptyState.fadeTo(true)
                binding.resultsList.fadeTo(false)
                lastStageOrdinal = -1
                adapter.submit(emptyList())
            }

            is ProcessingState.Running -> {
                binding.progressGroup.fadeTo(true)
                binding.pickButton.isEnabled = false
                binding.emptyState.fadeTo(false)
                binding.resultsList.fadeTo(false)
                renderProgress(state)
            }

            is ProcessingState.Done -> {
                binding.progressGroup.fadeTo(false)
                binding.pickButton.isEnabled = true
                lastStageOrdinal = -1
                val hasResults = state.results.isNotEmpty()
                binding.emptyState.fadeTo(!hasResults)
                adapter.submit(state.results)
                if (hasResults) {
                    binding.resultsList.scheduleLayoutAnimation()
                    binding.resultsList.fadeTo(true)
                } else {
                    binding.resultsList.fadeTo(false)
                }
                state.failures.firstOrNull()?.let {
                    snack(getString(R.string.processing_failed, it.displayName, it.message))
                }
            }
        }
    }

    private fun renderProgress(state: ProcessingState.Running) {
        binding.statusText.text = getString(R.string.processing_status, state.stage.label)
        binding.statusSubtext.text = getString(
            R.string.processing_subtext,
            state.videoDisplayName,
            state.videoIndex + 1,
            state.videoCount,
        )

        val fraction = state.fraction
        if (fraction == null) {
            binding.progressRing.isIndeterminate = true
            binding.progressPercent.text = ""
        } else {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            binding.progressRing.isIndeterminate = false
            binding.progressRing.setProgressCompat(percent, true)
            binding.progressPercent.text = getString(R.string.percent_format, percent)
        }

        if (state.stage.ordinal != lastStageOrdinal) {
            lastStageOrdinal = state.stage.ordinal
            updateStepper(state.stage.ordinal)
        }
    }

    /** Fills the stepper up to (and including) the stage currently running. */
    private fun updateStepper(activeOrdinal: Int) {
        val dots = listOf(
            binding.stageDot0,
            binding.stageDot1,
            binding.stageDot2,
            binding.stageDot3,
        )
        dots.forEachIndexed { index, dot ->
            val reached = index <= activeOrdinal
            dot.setBackgroundResource(
                if (reached) R.drawable.bg_stage_dot_active else R.drawable.bg_stage_dot_idle,
            )
            if (index == activeOrdinal) {
                dot.scaleY = 0.4f
                dot.animate().scaleY(1f).setDuration(280).start()
            } else {
                dot.scaleY = 1f
            }
        }
    }

    private fun saveWithPermission(result: VideoResult) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = result
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(result)
        }
    }

    private fun save(result: VideoResult) {
        lifecycleScope.launch {
            runCatching { CollageStore.saveToGallery(this@MainActivity, result.collage, result.displayName) }
                .onSuccess { snack(getString(R.string.saved_to_gallery)) }
                .onFailure { snack(getString(R.string.save_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun share(result: VideoResult) {
        lifecycleScope.launch {
            runCatching { CollageStore.shareableUri(this@MainActivity, result.collage, result.displayName) }
                .onSuccess { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
                }
                .onFailure { snack(getString(R.string.save_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_VIDEOS = 5
    }
}
