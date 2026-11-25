package com.example.translator.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.translator.LearnedCorrectionEntry
import com.example.translator.LearnedMemoryEntry
import com.example.translator.R
import com.example.translator.ui.components.OverChatSegmentedControl

/**
 * Hoja modal inferior limpia para administrar el diccionario personal, reglas manuales y memorias.
 *
 * Por qué: Sustituye el diálogo estrecho por un sheet espacioso y respirable manteniendo la misma firma pública.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryDialog(
    sourceLanguage: String,
    targetLanguage: String,
    glossaryEnabled: Boolean,
    learningEnabled: Boolean,
    corrections: List<LearnedCorrectionEntry>,
    memories: List<LearnedMemoryEntry>,
    onGlossaryChanged: (Boolean) -> Unit,
    onLearningChanged: (Boolean) -> Unit,
    onAddCorrection: (src: String, tgt: String, wrongTerm: String, preferredTerm: String) -> Boolean,
    onDeleteCorrection: (LearnedCorrectionEntry) -> Unit,
    onDeleteMemory: (LearnedMemoryEntry) -> Unit,
    onRequestResetAll: () -> Unit,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_personal_dictionary),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (corrections.isNotEmpty() || memories.isNotEmpty()) {
                        TextButton(
                            onClick = onRequestResetAll,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_reset_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = stringResource(R.string.pill_close_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    DictionaryToggleRow(
                        label = stringResource(R.string.dict_apply_saved_fixes),
                        checked = glossaryEnabled,
                        onCheckedChange = onGlossaryChanged
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    DictionaryToggleRow(
                        label = stringResource(R.string.dict_learn_from_edits),
                        checked = learningEnabled,
                        onCheckedChange = onLearningChanged
                    )
                }
            }

            OverChatSegmentedControl(
                options = listOf(
                    "words" to stringResource(R.string.dict_tab_words, corrections.size),
                    "phrases" to stringResource(R.string.dict_tab_phrases, memories.size)
                ),
                selectedValue = if (selectedTab == 0) "words" else "phrases",
                onSelected = { selectedTab = if (it == "words") 0 else 1 }
            )

            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text(stringResource(R.string.dict_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedTab == 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.dict_add_custom_rule),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = { directionOutgoing = !directionOutgoing },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_swap_horiz),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "${ruleSrc.uppercase()} → ${ruleTgt.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = wrongTerm,
                                onValueChange = { wrongTerm = it },
                                placeholder = { Text(stringResource(R.string.dict_placeholder_replace), style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = preferredTerm,
                                onValueChange = { preferredTerm = it },
                                placeholder = { Text(stringResource(R.string.dict_placeholder_with), style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (onAddCorrection(ruleSrc, ruleTgt, wrongTerm, preferredTerm)) {
                                        wrongTerm = ""
                                        preferredTerm = ""
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(stringResource(R.string.action_add), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if ((selectedTab == 0 && filteredCorrections.isEmpty()) || (selectedTab == 1 && filteredMemories.isEmpty())) {
                Text(
                    text = stringResource(
                        if (filterQuery.isBlank()) R.string.dict_empty_entries else R.string.dict_no_matching_entries
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp)
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
                                pairBadge = "${entry.sourceLang.uppercase()} → ${entry.targetLang.uppercase()} · ×${entry.hits}",
                                primaryLine = "${entry.rejected}  →  ${entry.preferred}",
                                onDelete = { onDeleteCorrection(entry) }
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    } else {
                        items(
                            items = filteredMemories,
                            key = { it.key }
                        ) { entry ->
                            GlossaryItemRow(
                                pairBadge = "${entry.sourceLang.uppercase()} → ${entry.targetLang.uppercase()}",
                                primaryLine = "${entry.sourceNormalized}  →  ${entry.translation}",
                                onDelete = { onDeleteMemory(entry) }
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pairBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = primaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
