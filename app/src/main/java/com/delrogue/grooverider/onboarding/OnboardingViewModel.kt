package com.delrogue.grooverider.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delrogue.grooverider.AppPrefs
import com.delrogue.grooverider.audio.AudioEngineService
import com.delrogue.grooverider.source.SourceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val sourceRepo = SourceRepository(app)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** Clean install -> audible texture in under 30s, no reading required (spec M8). */
    fun getStarted() {
        _busy.value = true
        viewModelScope.launch {
            AudioEngineService.start(getApplication())
            delay(400)   // let the stream open before pushing a source into it

            // A second, synthetic source so there is something to try a preset on.
            val demo = DemoSourceGenerator.generate()
            sourceRepo.importSynthetic(demo, channels = 1, sampleRate = 48000, name = "First Light demo")

            // Big River and the presets; the main screen then opens on Standing Room Only.
            FactoryInstaller.ensure(getApplication())
            AppPrefs.setOpenOnDefaultPreset(getApplication(), true)
            AppPrefs.setOnboarded(getApplication())
            _busy.value = false
            _done.value = true
        }
    }
}
