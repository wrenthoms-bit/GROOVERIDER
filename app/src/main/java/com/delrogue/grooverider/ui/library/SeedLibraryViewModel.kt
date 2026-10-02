package com.delrogue.grooverider.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delrogue.grooverider.seed.MutableParam
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.seed.SeedFileIO
import com.delrogue.grooverider.seed.SeedRepository
import com.delrogue.grooverider.source.SourceRepository
import com.delrogue.grooverider.seed.SeedShareFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SeedLibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SeedRepository(app)
    private val sources = SourceRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val allSeeds = repo.observeAll()
    val seeds: StateFlow<List<Seed>> = combine(allSeeds, _query) { list, q ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selected = MutableStateFlow<Seed?>(null)
    val selected: StateFlow<Seed?> = _selected.asStateFlow()

    private val _children = MutableStateFlow<List<Seed>>(emptyList())
    val children: StateFlow<List<Seed>> = _children.asStateFlow()

    private val _parent = MutableStateFlow<Seed?>(null)
    val parent: StateFlow<Seed?> = _parent.asStateFlow()

    private val _mutationCandidates = MutableStateFlow<List<Seed>>(emptyList())
    val mutationCandidates: StateFlow<List<Seed>> = _mutationCandidates.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun select(seed: Seed?) {
        _selected.value = seed
        _mutationCandidates.value = emptyList()
        if (seed == null) { _children.value = emptyList(); _parent.value = null; return }
        viewModelScope.launch {
            _children.value = repo.getChildren(seed)
            _parent.value = seed.parentId?.let { repo.getById(it) }
        }
    }

    fun toggleFavourite(seed: Seed) {
        viewModelScope.launch { repo.setFavourite(seed, !seed.favourite) }
    }

    fun toggleLock(seed: Seed, param: MutableParam) {
        viewModelScope.launch {
            val updated = seed.copy(lockedParams = param.locked(seed.lockedParams, !param.isLocked(seed.lockedParams)))
            repo.update(updated)
            if (_selected.value?.id == seed.id) _selected.value = updated
        }
    }

    fun delete(seed: Seed) {
        viewModelScope.launch {
            repo.delete(seed)
            if (_selected.value?.id == seed.id) select(null)
        }
    }

    /** Mutate at [amount]: six sensitivity-weighted children, laid out for audition (spec 3.6). */
    fun mutate(seed: Seed, amount: Float) {
        viewModelScope.launch {
            _mutationCandidates.value = repo.mutateAndSave(seed, amount)
        }
    }

    fun dismissMutationCandidates() { _mutationCandidates.value = emptyList() }

    fun breed(parentA: Seed, parentB: Seed, blend: Float, onDone: (Seed) -> Unit) {
        viewModelScope.launch { onDone(repo.breedAndSave(parentA, parentB, blend)) }
    }

    fun applyToSource(seed: Seed, newSourceHash: String, onDone: (Seed) -> Unit) {
        viewModelScope.launch { onDone(repo.applyToSource(seed, newSourceHash)) }
    }

    /** Writes to app-private storage and returns the file, ready for [ShareExporter]. */
    fun exportForSharing(seed: Seed, exportsDir: File): File {
        exportsDir.mkdirs()
        val file = File(exportsDir, "GR_${seed.name.replace(Regex("[^A-Za-z0-9]+"), "")}.grvr")
        val sourceName = sources.list().firstOrNull { it.hash == seed.sourceHash }?.name ?: ""
        SeedFileIO.exportToFile(seed, sourceName, file)
        return file
    }

    private val _importNote = MutableStateFlow<String?>(null)
    /** What the last import did, for the screen to show. */
    val importNote: StateFlow<String?> = _importNote.asStateFlow()
    fun dismissImportNote() { _importNote.value = null }

    /**
     * Imports a `.grvr` file, from this app or the web app. The seed goes on
     * the source it was made on if that is here (by content, then by name);
     * otherwise on the sound that is playing now.
     */
    fun importSeedText(json: String, onImported: (Seed) -> Unit) {
        viewModelScope.launch {
            var placedOn = ""
            var substituted = false
            val seed = repo.importSeedFile(json) { name, hash ->
                val all = sources.list()
                val match = all.firstOrNull { hash.isNotEmpty() && it.hash == hash }
                    ?: all.firstOrNull { name.isNotEmpty() && it.name.equals(name, ignoreCase = true) }
                val chosen = match ?: all.firstOrNull { it.hash == SourceRepository.engineSourceHash.value }
                    ?: return@importSeedFile null
                substituted = match == null
                placedOn = chosen.name
                val peaks = sources.peaks(chosen.hash)
                chosen.hash to ByteArray(256) { i -> (peaks.getOrElse(i * peaks.size / 256) { 0f }.coerceIn(0f, 1f) * 255f).toInt().toByte() }
            }
            _importNote.value = when {
                seed == null && SeedShareFormat.decode(json) != null ->
                    "That seed's sound is not on this phone. Load a sound on the Sources tab first, then import again."
                seed == null -> "That file is not a Grooverider seed."
                substituted -> "Imported \"${seed.name}\" onto \"$placedOn\" -- the sound it was made on is not on this phone."
                else -> "Imported \"${seed.name}\"."
            }
            seed?.let(onImported)
        }
    }
}
