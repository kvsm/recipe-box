package app.recipebox.data.db

import androidx.room.TypeConverter
import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.PrepTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun fromStrings(value: List<String>): String = json.encodeToString(value)
    @TypeConverter fun toStrings(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter fun fromIngredients(value: List<Ingredient>): String = json.encodeToString(value)
    @TypeConverter fun toIngredients(value: String): List<Ingredient> = json.decodeFromString(value)

    @TypeConverter fun fromSections(value: List<InstructionSection>): String = json.encodeToString(value)
    @TypeConverter fun toSections(value: String): List<InstructionSection> = json.decodeFromString(value)

    @TypeConverter fun fromPrep(value: List<PrepTask>): String = json.encodeToString(value)
    @TypeConverter fun toPrep(value: String): List<PrepTask> = json.decodeFromString(value)
}
