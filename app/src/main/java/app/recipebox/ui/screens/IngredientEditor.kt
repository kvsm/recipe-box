package app.recipebox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.amountForEditing
import app.recipebox.data.model.editedDisplay
import app.recipebox.data.model.withEditedAmount
import app.recipebox.importer.IngredientParser
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.theme.Tin

/**
 * Fix one ingredient line by hand. The line as the recipe wrote it stays
 * pinned at the top, with a live preview of how it'll read once saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientEditor(
    ingredient: Ingredient,
    onSave: (Ingredient) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(ingredient.name) }
    var amount by rememberSaveable { mutableStateOf(ingredient.amountForEditing()) }
    var unit by rememberSaveable { mutableStateOf(ingredient.unit.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(ingredient.note.orEmpty()) }

    fun build(): Ingredient = ingredient
        .withEditedAmount(amount)
        .copy(
            name = name.trim(),
            unit = IngredientParser.canonicalUnit(unit),
            note = note.trim().ifEmpty { null },
            edited = true,
        )
    val preview = if (name.isBlank()) null else build()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tin.Card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Fix this ingredient", style = MaterialTheme.typography.headlineSmall, color = Tin.Ink)

            Column(
                Modifier.fillMaxWidth()
                    .background(Tin.Paper, RoundedCornerShape(14.dp))
                    .border(2.dp, Tin.Ink, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("The recipe says", style = MaterialTheme.typography.labelMedium, color = Tin.Ink)
                Text(ingredient.raw, style = MaterialTheme.typography.bodyLarge, color = Tin.Ink)
            }

            Field("Item", name, { name = it }, "What goes on the shopping list, e.g. onions")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Amount", amount, { amount = it }, "2, 1½, 2–3 or small pack", Modifier.weight(1f))
                Field("Unit", unit, { unit = it }, "g, tbsp, clove…", Modifier.weight(1f))
            }
            Field("Note", note, { note = it }, "finely chopped, optional…")

            preview?.let {
                Text("Reads as: ${it.editedDisplay()}", style = MaterialTheme.typography.bodyMedium, color = Tin.Ink)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StickerButton(
                    onClick = { preview?.let(onSave) },
                    enabled = preview != null,
                    minHeight = 52.dp,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                StickerButton(
                    onClick = { onSave(IngredientParser.parse(ingredient.raw)) },
                    fill = Tin.Card,
                    minHeight = 52.dp,
                ) { Text("Reset") }
            }
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium, color = Tin.Ink)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Tin.Paper,
            unfocusedContainerColor = Tin.Paper,
            focusedBorderColor = Tin.Ink,
            unfocusedBorderColor = Tin.Ink,
            focusedLabelColor = Tin.Ink,
        ),
        modifier = modifier,
    )
}
