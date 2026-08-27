package com.delrogue.grooverider.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delrogue.grooverider.render.ShareExporter
import com.delrogue.grooverider.seed.MutableParam
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.source.SourceRecord
import com.delrogue.grooverider.ui.EngineViewModel
import com.delrogue.grooverider.ui.source.SourceViewModel
import java.io.File

/**
 * The library (spec M7): grid of Seeds, drill-down lineage, mutation audition.
 * Portrait -- performance lives on the Cloud screen, browsing lives here
 * (spec 5.1).
 */
@Composable
fun SeedLibraryScreen(
    modifier: Modifier = Modifier,
    vm: SeedLibraryViewModel = viewModel(),
    engineVm: EngineViewModel = viewModel(),
    sourceVm: SourceViewModel = viewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val seeds by vm.seeds.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val children by vm.children.collectAsStateWithLifecycle()
    val parent by vm.parent.collectAsStateWithLifecycle()
    val mutationCandidates by vm.mutationCandidates.collectAsStateWithLifecycle()
    val sources by sourceVm.sources.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickingSourceFor by remember { mutableStateOf<Seed?>(null) }

    Column(modifier.fillMaxSize().background(Color(0xFF0A0A0C)).padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            label = { Text("Search seeds") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer4dp()
        Text("${seeds.size} seeds", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.bodySmall)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(seeds, key = { it.id }) { seed ->
                SeedCard(seed = seed, onClick = { vm.select(seed) }, onFavourite = { vm.toggleFavourite(seed) })
            }
        }

        selected?.let { seed ->
            SeedDetail(
                seed = seed,
                parent = parent,
                children = children,
                mutationCandidates = mutationCandidates,
                onClose = { vm.select(null) },
                onSelectSeed = vm::select,
                onLoad = { engineVm.loadSeed(it) },
                onFavourite = { vm.toggleFavourite(it) },
                onDelete = { vm.delete(it) },
                onMutate = { amount -> vm.mutate(seed, amount) },
                onDismissMutations = vm::dismissMutationCandidates,
                onToggleLock = { p -> vm.toggleLock(seed, p) },
                onApplyToSource = { pickingSourceFor = seed },
                onExport = {
                    val file = vm.exportForSharing(seed, File(context.filesDir, "seed_exports"))
                    ShareExporter.share(context, file, mimeType = "application/json", chooserTitle = "Send Seed")
                },
            )
        }

        pickingSourceFor?.let { seed ->
            SourcePickerDialog(
                sources = sources,
                onPick = { record ->
                    vm.applyToSource(seed, record.hash) { vm.select(it) }
                    pickingSourceFor = null
                },
                onDismiss = { pickingSourceFor = null },
            )
        }
    }
}

@Composable
private fun SourcePickerDialog(sources: List<SourceRecord>, onPick: (SourceRecord) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.background(Color(0xFF1A1A1E)).padding(16.dp).clickable(enabled = false) {},
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Apply to...", color = Color.White, style = MaterialTheme.typography.titleMedium)
            sources.forEach { record ->
                Text(
                    record.name,
                    color = Color.White,
                    modifier = Modifier.clickable { onPick(record) }.padding(8.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Spacer4dp() = Box(Modifier.height(4.dp))

@Composable
private fun SeedCard(seed: Seed, onClick: () -> Unit, onFavourite: () -> Unit) {
    Card(Modifier.padding(4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp)) {
            ThumbnailCanvas(seed.waveformThumb, Modifier.fillMaxWidth().height(40.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(seed.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    if (seed.favourite) "★" else "☆",
                    color = if (seed.favourite) Color(0xFFE0C84D) else Color(0xFF6A6A70),
                    modifier = Modifier.clickable(onClick = onFavourite),
                )
            }
        }
    }
}

@Composable
private fun ThumbnailCanvas(thumb: ByteArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (thumb.isEmpty()) return@Canvas
        val barW = size.width / thumb.size
        for (i in thumb.indices) {
            val amp = (thumb[i].toInt() and 0xFF) / 255f * size.height
            drawRect(
                color = Color(0xFF7FE0C8),
                topLeft = Offset(i * barW, (size.height - amp) / 2f),
                size = Size(barW * 0.8f, amp),
            )
        }
    }
}

@Composable
private fun SeedDetail(
    seed: Seed,
    parent: Seed?,
    children: List<Seed>,
    mutationCandidates: List<Seed>,
    onClose: () -> Unit,
    onSelectSeed: (Seed) -> Unit,
    onLoad: (Seed) -> Unit,
    onFavourite: (Seed) -> Unit,
    onDelete: (Seed) -> Unit,
    onMutate: (Float) -> Unit,
    onDismissMutations: () -> Unit,
    onToggleLock: (MutableParam) -> Unit,
    onApplyToSource: () -> Unit,
    onExport: () -> Unit,
) {
    var mutationAmount by remember { mutableFloatStateOf(0.3f) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF17171B))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(seed.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("✕", color = Color.White, modifier = Modifier.clickable(onClick = onClose))
        }

        if (parent != null) {
            Text(
                "↑ ${parent.name}",
                color = Color(0xFF3FA7FF),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { onSelectSeed(parent) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onLoad(seed) }) { Text("Load") }
            OutlinedButton(onClick = { onFavourite(seed) }) { Text(if (seed.favourite) "Unfavourite" else "Favourite") }
            OutlinedButton(onClick = { onDelete(seed) }) { Text("Delete") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onApplyToSource) { Text("Apply to...") }
            OutlinedButton(onClick = onExport) { Text("Export .grvr") }
        }

        if (children.isNotEmpty()) {
            Text("Children (${children.size})", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyRowItems(children, key = { it.id }) { child ->
                    Text(
                        child.name,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF2A2A2E))
                            .clickable { onSelectSeed(child) }
                            .padding(8.dp),
                    )
                }
            }
        }

        Text("Mutate", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = mutationAmount, onValueChange = { mutationAmount = it }, modifier = Modifier.weight(1f))
            Text("%.2f".format(mutationAmount), color = Color.White)
            Button(onClick = { onMutate(mutationAmount) }) { Text("Go") }
        }

        Text(
            "Locked params (tap to protect from mutation):",
            color = Color(0xFF9AA0A6),
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lazyRowItems(MutableParam.entries) { p ->
                val locked = p.isLocked(seed.lockedParams)
                Text(
                    (if (locked) "🔒 " else "") + p.name.lowercase(),
                    color = if (locked) Color(0xFF3FA7FF) else Color(0xFF6A6A70),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .background(Color(0xFF222226))
                        .clickable { onToggleLock(p) }
                        .padding(6.dp),
                )
            }
        }

        if (mutationCandidates.isNotEmpty()) {
            Text("Six children -- audition and pick", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyRowItems(mutationCandidates, key = { it.id }) { child ->
                    Card(Modifier.size(100.dp).clickable { onSelectSeed(child); onDismissMutations() }) {
                        Column(Modifier.padding(6.dp)) {
                            ThumbnailCanvas(child.waveformThumb, Modifier.fillMaxWidth().height(28.dp))
                            Text(child.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
