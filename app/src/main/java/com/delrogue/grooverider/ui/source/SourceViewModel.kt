package com.delrogue.grooverider.ui.source

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delrogue.grooverider.audio.AudioEngineService
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.source.SourceRecord
import com.delrogue.grooverider.source.SourceRepository
import android.net.Uri
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SelectedSource(
    val record: SourceRecord,
    val engineFrames: Long,      // frames after resample to the engine rate
    val peaks: FloatArray,
    val inFrame: Long,
    val outFrame: Long,
)

class SourceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SourceRepository(app)

    private val _sources = MutableStateFlow<List<SourceRecord>>(emptyList())
    val sources: StateFlow<List<SourceRecord>> = _sources.asStateFlow()

    private val _selected = MutableStateFlow<SelectedSource?>(null)
    val selected: StateFlow<SelectedSource?> = _selected.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _micError = MutableStateFlow<String?>(null)
    val micError: StateFlow<String?> = _micError.asStateFlow()

    private val _playhead = MutableStateFlow(0L)
    val playhead: StateFlow<Long> = _playhead.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var micJob: Job? = null
    private var playheadJob: Job? = null

    init { refresh() }

    fun refresh() { _sources.value = repo.list() }

    fun importAudio(uri: Uri, name: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.importAudio(uri, name) }
                .onSuccess { rec -> refresh(); select(rec) }
            _busy.value = false
        }
    }

    fun toggleMic() {
        if (_recording.value) stopMic() else startMic()
    }

    private fun startMic() {
        _micError.value = null
        // The recorder lives on the native engine singleton, which is only
        // created by AudioEngineService (started explicitly from the Engine
        // tab, or once during onboarding). Landing straight on Sources and
        // hitting "Record mic" otherwise finds no recorder at all. Kick the
        // service here too and retry briefly while it spins up.
        AudioEngineService.start(getApplication())
        micJob = viewModelScope.launch {
            var attempts = 0
            while (!GrooveriderEngine.micStart(60.0)) {
                attempts++
                if (attempts >= 20) {
                    _micError.value = "Couldn't start the mic. Check that mic permission is " +
                        "granted and that no other app is using it."
                    return@launch
                }
                delay(50)
            }
            _recording.value = true
            while (GrooveriderEngine.micIsRecording()) {
                _micLevel.value = GrooveriderEngine.micLevel()
                delay(50)
            }
            // hit the 60 s cap on its own
            if (_recording.value) stopMic()
        }
    }

    private fun stopMic() {
        _recording.value = false
        micJob?.cancel(); micJob = null
        GrooveriderEngine.micStop()
        viewModelScope.launch {
            _busy.value = true
            val stamp = System.currentTimeMillis()
            repo.commitMicTake("Mic take $stamp")?.let { rec -> refresh(); select(rec) }
            _busy.value = false
        }
    }

    fun select(record: SourceRecord) {
        viewModelScope.launch {
            _busy.value = true
            val engineFrames = run {
                repo.loadIntoEngine(record.hash)
                // engine reports its own frame count via preview region default
                GrooveriderEngine.previewPosition()  // touch to ensure loaded
                val sr = GrooveriderEngine.engineSampleRate().coerceAtLeast(1)
                record.frames * sr / record.sampleRate.coerceAtLeast(1)
            }
            val peaks = repo.peaks(record.hash)
            _selected.value = SelectedSource(record, engineFrames, peaks, 0, engineFrames)
            GrooveriderEngine.previewRegion(0, engineFrames)
            GrooveriderEngine.previewLoop(true)
            _busy.value = false
        }
    }

    fun setTrim(inFrame: Long, outFrame: Long) {
        val s = _selected.value ?: return
        val i = inFrame.coerceIn(0, s.engineFrames)
        val o = outFrame.coerceIn(i, s.engineFrames)
        _selected.value = s.copy(inFrame = i, outFrame = o)
        GrooveriderEngine.previewRegion(i, o)
    }

    fun play() {
        val s = _selected.value ?: return
        GrooveriderEngine.previewSeek(s.inFrame)
        GrooveriderEngine.previewPlay(true)
        startPlayheadPoll()
    }

    fun stop() {
        GrooveriderEngine.previewPlay(false)
        playheadJob?.cancel(); playheadJob = null
    }

    private fun startPlayheadPoll() {
        if (playheadJob != null) return
        playheadJob = viewModelScope.launch {
            while (GrooveriderEngine.previewIsPlaying()) {
                _playhead.value = GrooveriderEngine.previewPosition()
                delay(33)
            }
            _playhead.value = GrooveriderEngine.previewPosition()
            playheadJob = null
        }
    }

    fun deleteSelected() {
        val s = _selected.value ?: return
        stop(); GrooveriderEngine.clearSource()
        repo.delete(s.record.hash)
        _selected.value = null
        refresh()
    }

    override fun onCleared() {
        micJob?.cancel(); playheadJob?.cancel()
        super.onCleared()
    }
}
