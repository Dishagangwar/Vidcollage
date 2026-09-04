package com.example.vidcollage

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vidcollage.databinding.DialogCollagePreviewBinding
import com.example.vidcollage.databinding.ItemPersonBinding
import com.example.vidcollage.databinding.ItemQualityBarBinding
import com.example.vidcollage.databinding.ItemResultBinding
import com.example.vidcollage.databinding.SheetPersonBinding
import com.example.vidcollage.pipeline.Person
import com.example.vidcollage.pipeline.VideoResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import java.util.concurrent.TimeUnit

/** One card per processed video: the people it found, the collage, and the save/share actions. */
class ResultsAdapter(
    private val onSave: (VideoResult) -> Unit,
    private val onShare: (VideoResult) -> Unit,
) : RecyclerView.Adapter<ResultsAdapter.ResultViewHolder>() {

    private var results: List<VideoResult> = emptyList()

    fun submit(newResults: List<VideoResult>) {
        results = newResults
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) = holder.bind(results[position])

    override fun getItemCount(): Int = results.size

    inner class ResultViewHolder(private val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: VideoResult) {
            val context = binding.root.context
            val resources = context.resources

            binding.videoName.text = result.displayName
            binding.videoDuration.text = formatTimestamp(result.durationMs)
            binding.peopleStat.text =
                resources.getQuantityString(R.plurals.people_count, result.people.size, result.people.size)
            binding.appearanceStat.text = resources.getQuantityString(
                R.plurals.appearance_count,
                result.totalAppearances,
                result.totalAppearances,
            )
            binding.framesStat.text = context.getString(R.string.frames_analysed, result.framesAnalysed)

            binding.collageImage.setImageBitmap(result.collage)
            // The card header reuses the collage as a thumbnail, so there is nothing extra to decode.
            binding.videoThumb.setImageBitmap(result.collage)

            binding.peopleHeader.visibility = if (result.people.isEmpty()) View.GONE else View.VISIBLE
            binding.peopleList.visibility = binding.peopleHeader.visibility
            binding.peopleList.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            binding.peopleList.adapter = PeopleAdapter(result.people) { person ->
                showPersonSheet(context, person)
            }
            binding.peopleList.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_pop_in)
            binding.peopleList.scheduleLayoutAnimation()

            binding.collageFrame.addPressBounce(scale = 0.985f)
            binding.collageFrame.setOnClickListener {
                it.tick()
                showCollagePreview(context, result)
            }

            binding.saveButton.setOnClickListener {
                it.tick()
                onSave(result)
            }
            binding.shareButton.setOnClickListener {
                it.tick()
                onShare(result)
            }
        }
    }
}

/** The horizontal strip of faces; each one opens the detail sheet. */
private class PeopleAdapter(
    private val people: List<Person>,
    private val onPersonClick: (Person) -> Unit,
) : RecyclerView.Adapter<PeopleAdapter.PersonViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        val binding = ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PersonViewHolder(binding, onPersonClick)
    }

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) = holder.bind(people[position])

    override fun getItemCount(): Int = people.size

    class PersonViewHolder(
        private val binding: ItemPersonBinding,
        private val onPersonClick: (Person) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(person: Person) {
            val context = binding.root.context
            binding.faceImage.setImageBitmap(person.shot.bitmap)
            binding.personLabel.text = person.label
            binding.appearanceBadge.text = person.appearanceCount.toString()
            binding.firstSeen.text = person.appearances.firstOrNull()
                ?.let { context.getString(R.string.first_seen_at, formatTimestamp(it.first)) }
                .orEmpty()

            val stamps = person.appearances.joinToString(", ") { formatTimestamp(it.first) }
            binding.root.contentDescription =
                context.getString(R.string.person_content_description, person.label, stamps)
            TooltipCompat.setTooltipText(binding.root, stamps)

            binding.root.addPressBounce(scale = 0.92f)
            binding.root.setOnClickListener {
                it.tick()
                onPersonClick(person)
            }
        }
    }
}

/** Face, timeline, and a breakdown of why this frame scored best for the person. */
private fun showPersonSheet(context: Context, person: Person) {
    val binding = SheetPersonBinding.inflate(LayoutInflater.from(context))
    val resources = context.resources

    binding.sheetFace.setImageBitmap(person.shot.bitmap)
    binding.sheetLabel.text = person.label
    binding.sheetSummary.text = listOf(
        resources.getQuantityString(
            R.plurals.appearance_count,
            person.appearanceCount,
            person.appearanceCount,
        ),
        context.getString(R.string.best_shot_at, formatTimestamp(person.shot.timestampMs)),
    ).joinToString(" · ")

    person.appearances.forEach { range ->
        val chip = Chip(context).apply {
            text = formatRange(range)
            isClickable = false
            isCheckable = false
        }
        binding.sheetTimestamps.addView(chip)
    }

    val quality = person.shot.quality
    listOf(
        R.string.quality_frontality to quality.frontality,
        R.string.quality_sharpness to quality.sharpness,
        R.string.quality_eyes_open to quality.eyesOpen,
        R.string.quality_smiling to quality.smiling,
        R.string.quality_size to quality.size,
    ).forEach { (labelRes, value) ->
        val row = ItemQualityBarBinding.inflate(
            LayoutInflater.from(context),
            binding.sheetQuality,
            false,
        )
        row.qualityLabel.setText(labelRes)
        row.qualityBar.progress = 0
        binding.sheetQuality.addView(row.root)
        // Animate from zero on the next frame so the bars visibly fill as the sheet settles.
        row.qualityBar.post {
            row.qualityBar.setProgressCompat((value.coerceIn(0f, 1f) * 100).toInt(), true)
        }
    }

    BottomSheetDialog(context).apply {
        setContentView(binding.root)
        show()
    }
}

/** Full-screen collage viewer; tapping anywhere closes it. */
private fun showCollagePreview(context: Context, result: VideoResult) {
    val binding = DialogCollagePreviewBinding.inflate(LayoutInflater.from(context))
    binding.previewImage.setImageBitmap(result.collage)
    binding.previewCaption.text = context.getString(
        R.string.preview_caption,
        result.displayName,
        context.getString(R.string.tap_to_close),
    )

    val dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    binding.previewRoot.setOnClickListener { dialog.dismiss() }
    binding.previewClose.setOnClickListener { dialog.dismiss() }

    binding.previewImage.alpha = 0f
    binding.previewImage.scaleX = 0.92f
    binding.previewImage.scaleY = 0.92f
    binding.previewImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    dialog.show()
}

/** "0:07" style stamps for the per-appearance tooltip on each face. */
fun formatTimestamp(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** "0:07 – 0:12", collapsing to a single stamp when the appearance is a blink. */
private fun formatRange(range: LongRange): String {
    val start = formatTimestamp(range.first)
    val end = formatTimestamp(range.last)
    return if (start == end) start else "$start – $end"
}
