package com.example.simple.manymanager

import android.content.Context
import android.content.DialogInterface
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.simple.manymanager.domain.DataError
import com.example.simple.manymanager.domain.DataResult

/** Window lifetime only. The ViewModel owns the form and operation across recreation. */
class CategoryCreationDialog(
    context: Context,
    onNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    private val input = EditText(context).apply {
        id = R.id.categoryNameInput
        hint = context.getString(R.string.category_name)
        setSingleLine(true)
        val density = resources.displayMetrics.density
        setPadding((24 * density).toInt(), (14 * density).toInt(),
            (24 * density).toInt(), (14 * density).toInt())
        setTextColor(ContextCompat.getColor(context, R.color.text))
        setHintTextColor(ContextCompat.getColor(context, R.color.muted))
    }
    val dialog: AlertDialog = AlertDialog.Builder(context)
        .setTitle(R.string.add_category_action).setView(input)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.add_category, null).create()

    init {
        input.doAfterTextChanged { onNameChanged(it.toString()) }
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_ESCAPE) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled == true) onCancel()
                true
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { onSubmit() }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { onCancel() }
        }
    }

    fun show() { dialog.showWithRoundedSurface() }

    fun render(state: CategoryCreationState) {
        dialog.setTitle(if (state.categoryId != null) R.string.rename_category
            else if (state.parentId == null) R.string.add_category_action else R.string.add_subcategory)
        if (input.text.toString() != state.name) {
            input.setText(state.name)
            input.setSelection(input.length())
        }
        input.isEnabled = !state.submitting && !state.interrupted
        dialog.setCancelable(!state.submitting)
        dialog.setCanceledOnTouchOutside(!state.submitting)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply {
            isEnabled = !state.submitting && !state.interrupted && state.result !is DataResult.Success
            setText(when {
                state.submitting -> R.string.category_saving
                state.categoryId != null -> R.string.save_expense_changes
                else -> R.string.add_category
            })
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = !state.submitting
        val error = (state.result as? DataResult.Failure)?.error
        input.error = when {
            error == DataError.BlankName -> input.context.getString(R.string.category_name_required)
            error != null -> input.context.getString(R.string.category_save_error)
            state.interrupted -> input.context.getString(R.string.category_save_interrupted)
            else -> null
        }
    }

    /** Detach a window for configuration/destruction without cancelling or retrying a write. */
    fun dispose() {
        dialog.setOnCancelListener(null)
        dialog.dismiss()
    }
}
