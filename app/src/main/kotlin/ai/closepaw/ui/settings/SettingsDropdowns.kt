package ai.closepaw.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    val matched = modelOptions.find { it.first == selectedModel }
    val selectedDisplayName = matched?.second ?: "Select a model"

    SettingsDropdown(
        label = "Model",
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        options = modelOptions,
        isSelected = { (modelId, _) -> modelId == selectedModel },
        onOptionSelected = { (modelId, _) -> onModelChange(modelId) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}
