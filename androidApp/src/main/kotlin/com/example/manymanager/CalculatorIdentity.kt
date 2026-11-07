package com.example.simple.manymanager

import android.content.Context
import android.content.ContextWrapper

/** Labels are presentation only; persistent calculator IDs never depend on language. */
fun Context.calculatorName(): String {
    var owner: Context = this
    while (owner is ContextWrapper) {
        if (owner is MainActivity) return owner.calculatorDisplayName
        owner = owner.baseContext
    }
    return getString(R.string.main_calculator)
}
fun Context.calculatorTitle(title: String): String = title + " · " + calculatorName()
