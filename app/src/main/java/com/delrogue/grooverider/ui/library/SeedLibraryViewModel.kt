package com.delrogue.grooverider.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delrogue.grooverider.seed.MutableParam
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.seed.SeedFileIO
import com.delrogue.grooverider.seed.SeedRepository
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
        val file = File(exportsDir, "${seed.name.replace(" ", "_")}.grvr")
        SeedFileIO.exportToFile(seed, file)
        return file
    }

    fun importFromFile(file: File) {
        viewModelScope.launch { SeedFileIO.importFromFile(file)?.let { repo.importSeed(it) } }
    }
}
