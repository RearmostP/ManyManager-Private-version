package com.example.simple.manymanager

import android.content.Context
import android.content.DialogInterface
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.example.simple.manymanager.domain.DataResult

/** Payment methods are names only; their identity survives renaming. */
class SourceCreationDialog(
    context: Context,
    onNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    private val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_add_source, null)
    private val input = view.findViewById<EditText>(R.id.addSourceName)
    val dialog = AlertDialog.Builder(context).setView(view)
        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.add_source, null).create()
    init {
        input.doAfterTextChanged { onNameChanged(it.toString()) }
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { onSubmit() }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { onCancel() }
        }
    }
    fun show() { dialog.setTitle(input.context.calculatorName()); dialog.showWithRoundedSurface() }
    fun render(state: SourceCreationState) {
        if (input.text.toString() != state.name) input.setText(state.name)
        input.isEnabled = !state.submitting && !state.interrupted
        input.error = if (state.result is DataResult.Failure) input.context.getString(R.string.source_save_error) else null
        if (state.interrupted) input.error = input.context.getString(R.string.source_save_interrupted)
        dialog.setCancelable(!state.submitting)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = !state.submitting
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
            !state.submitting && !state.interrupted && state.name.isNotBlank()
    }
    fun dispose() { dialog.setOnCancelListener(null); dialog.dismiss() }
}

class SourceEditingDialog(
    context: Context,
    onNameChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    private val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_edit_source, null)
    private val input = view.findViewById<EditText>(R.id.editSourceName)
    val dialog = AlertDialog.Builder(context).setView(view)
        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save_expense_changes, null).create()
    init {
        input.doAfterTextChanged { onNameChanged(it.toString()) }
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { onSubmit() }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { onCancel() }
        }
    }
    fun show() { dialog.setTitle(input.context.calculatorName()); dialog.showWithRoundedSurface() }
    fun render(state: SourceEditingState) {
        if (input.text.toString() != state.name) input.setText(state.name)
        input.isEnabled = !state.submitting && !state.interrupted
        input.error = if (state.submitResult is DataResult.Failure) input.context.getString(R.string.source_save_error) else null
        if (state.interrupted) input.error = input.context.getString(R.string.source_save_interrupted)
        dialog.setCancelable(!state.submitting)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = !state.submitting
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
            !state.submitting && !state.interrupted && state.name.isNotBlank()
    }
    fun dispose() { dialog.setOnCancelListener(null); dialog.dismiss() }
}
