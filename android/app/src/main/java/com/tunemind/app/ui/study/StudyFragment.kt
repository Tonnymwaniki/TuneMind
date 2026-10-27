package com.tunemind.app.ui.study

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.tunemind.app.R
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.study.StudyRepository
import com.tunemind.app.domain.study.StudyStatus
import kotlinx.coroutines.launch

class StudyFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_study, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val studyRepo = StudyRepository(TuneMindDatabase.getInstance(requireContext()))
        val tvStatus = view.findViewById<TextView>(R.id.tv_status)
        val tvDetails = view.findViewById<TextView>(R.id.tv_details)
        val tvProgress = view.findViewById<TextView>(R.id.tv_progress)
        val btnStart = view.findViewById<MaterialButton>(R.id.btn_start)
        val cardProgress = view.findViewById<View>(R.id.card_progress)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { studyRepo.observeStatus().collect { study ->
                    val status = study?.status?.let { runCatching { StudyStatus.valueOf(it) }.getOrNull() } ?: StudyStatus.NOT_STARTED
                    when (status) {
                        StudyStatus.NOT_STARTED -> {
                            tvStatus.text = "Not started"
                            tvDetails.text = "A 7-day study tracks how your listening patterns evolve. Start when you're ready."
                            btnStart.visibility = View.VISIBLE
                            cardProgress.visibility = View.GONE
                        }
                        StudyStatus.ACTIVE -> {
                            val day = (study!!.completedDays + 1).coerceAtMost(study.totalDays)
                            tvStatus.text = "Day $day of ${study.totalDays}"
                            tvDetails.text = "Study in progress. Continue your listening as usual."
                            tvProgress.text = buildString {
                                appendLine("Started: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(study.startDateMs!!))}")
                                appendLine("Ends: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(study.endDateMs!!))}")
                                appendLine("Days completed: ${study.completedDays}")
                            }
                            btnStart.visibility = View.GONE
                            cardProgress.visibility = View.VISIBLE
                        }
                        StudyStatus.COMPLETED -> {
                            tvStatus.text = "Completed"
                            tvDetails.text = "Your 7-day study is complete. Review your report in the Study tab."
                            btnStart.text = "Reset Study"
                            btnStart.visibility = View.VISIBLE
                            cardProgress.visibility = View.VISIBLE
                            tvProgress.text = "Study completed successfully."
                        }
                        else -> {
                            tvStatus.text = "Interrupted"
                            tvDetails.text = "The study was interrupted."
                            btnStart.text = "Restart Study"
                            btnStart.visibility = View.VISIBLE
                        }
                    }
                } }
            }
        }

        btnStart.setOnClickListener {
            lifecycleScope.launch {
                val current = studyRepo.getStatus()
                if (current?.status == StudyStatus.COMPLETED.name || current?.status == StudyStatus.INTERRUPTED.name) {
                    studyRepo.reset()
                }
                studyRepo.start()
            }
        }
    }
}
