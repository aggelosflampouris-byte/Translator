package com.example.translator.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.translator.LearnedCorrectionEntry
import com.example.translator.LearnedMemoryEntry

/**
 * Diálogo modal para inspeccionar, añadir o eliminar reglas aprendidas y frases recordadas del glosario.
 *
 * Por qué: Permite corregir errores puntuales sin obligar al usuario a reiniciar todo el aprendizaje local.
 */
@Composable
fun GlossaryDialog(
    sourceLanguage: String,
    targetLanguage: String,
    corrections: List<LearnedCorrectionEntry>,
    memories: List<LearnedMemoryEntry>,
    onAddCorrection: (src: String, tgt: String, wrongTerm: String, preferredTerm: String) -> Boolean,
    onDeleteCorrection: (LearnedCorrectionEntry) -> Unit,
    onDeleteMemory: (LearnedMemoryEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var filterQuery by remember { mutableStateOf("") }
    var wrongTerm by remember { mutableStateOf("") }
    var preferredTerm by remember { mutableStateOf("") }
    var directionOutgoing by remember { mutableStateOf(false) }

    val filteredCorrections = remember(corrections, filterQuery) {
        val q = filterQuery.trim().lowercase()
        if (q.isEmpty()) corrections else corrections.filter {
            it.rejected.lowercase().contains(q) || it.preferred.lowercase().contains(q)
        }
    }
    val filteredMemories = remember(memories, filterQuery) {
        val q = filterQuery.trim().lowercase()
        if (q.isEmpty()) memories else memories.filter {
            it.sourceNormalized.lowercase().contains(q) || it.translation.lowercase().contains(q)
        }
    }

    val ruleSrc = if (directionOutgoing) targetLanguage else sourceLanguage
    val ruleTgt = if (directionOutgoing) sourceLanguage else targetLanguage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Glossary") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = {
                            Text(
                                text = "Words (${corrections.size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = {
                            Text(
                                text = "Phrases (${memories.size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = { Text("Search glossary...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (selectedTab == 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedCard(
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Add custom rule (${ruleSrc.uppercase()} → ${ruleTgt.uppercase()})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(
                                    onClick = { directionOutgoing = !directionOutgoing },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("Flip", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = wrongTerm,
                                    onValueChange = { wrongTerm = it },
                                    placeholder = { Text("Replace") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = preferredTerm,
                                    onValueChange = { preferredTerm = it },
                                    placeholder = { Text("With") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (onAddCorrection(ruleSrc, ruleTgt, wrongTerm, preferredTerm)) {
                                            wrongTerm = ""
                                            preferredTerm = ""
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if ((selectedTab == 0 && filteredCorrections.isEmpty()) || (selectedTab == 1 && filteredMemories.isEmpty())) {
                    Text(
                        text = if (filterQuery.isBlank()) "No entries yet." else "No matching entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        if (selectedTab == 0) {
                            items(
                                items = filteredCorrections,
                                key = { "${it.sourceLang}|${it.targetLang}|${it.rejected}" }
                            ) { entry ->
                                GlossaryItemRow(
                                    pairBadge = "${entry.sourceLang.uppercase()}→${entry.targetLang.uppercase()} · ×${entry.hits}",
                                    primaryLine = "${entry.rejected}  →  ${entry.preferred}",
                                    onDelete = { onDeleteCorrection(entry) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        } else {
                            items(
                                items = filteredMemories,
                                key = { it.key }
                            ) { entry ->
                                GlossaryItemRow(
                                    pairBadge = "${entry.sourceLang.uppercase()}→${entry.targetLang.uppercase()}",
                                    primaryLine = "${entry.sourceNormalized}  →  ${entry.translation}",
                                    onDelete = { onDeleteMemory(entry) }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun GlossaryItemRow(
    pairBadge: String,
    primaryLine: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pairBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = primaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Delete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

