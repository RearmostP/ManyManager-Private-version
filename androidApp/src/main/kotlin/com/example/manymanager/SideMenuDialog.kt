package com.example.simple.manymanager

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ScrollView
import android.view.animation.AccelerateInterpolator
import kotlin.math.min

/** Owns the side menu window; navigation is delegated explicitly to its caller. */
class SideMenuDialog(
    activity: Activity,
    anchor: View,
    onNavigate: (Int) -> Unit
) : Dialog(activity) {
    private val resources get() = context.resources
    private val panel: ScrollView
    private val host: FrameLayout
    private val menuWidth: Int
    private val opensFromRight: Boolean
    private var isDismissing = false
    private var dismissed = false

    init {
        setOwnerActivity(activity)
        val dialog = this
        val button = anchor
        val position = IntArray(2)
        button.getLocationOnScreen(position)
        val bounds = activity.window.windowManager.currentWindowMetrics.bounds
        opensFromRight = position[0] + button.width / 2 > bounds.centerX()
        menuWidth = min(dp(320), (bounds.width() * 0.85f).toInt())
        val content = layoutInflater.inflate(R.layout.dialog_menu, null)
        panel = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundResource(if (opensFromRight) R.drawable.menu_from_right else R.drawable.menu_from_left)
            clipToOutline = true
            addView(content)
        }
        host = FrameLayout(context).apply {
            layoutDirection = resources.configuration.layoutDirection
            addView(panel, FrameLayout.LayoutParams(
                menuWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (opensFromRight) Gravity.RIGHT else Gravity.LEFT
            ))
            setOnClickListener { dialog.dismiss() }
        }
        content.isClickable = true
        content.findViewById<View>(R.id.closeDialog).setOnClickListener { dialog.dismiss() }
        host.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsets.CONSUMED
        }
        dialog.setContentView(host)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.38f
                windowAnimations = 0
            }
            setDecorFitsSystemWindows(false)
        }

        listOf(R.id.nav0, R.id.nav1, R.id.nav2, R.id.nav3, R.id.nav4, R.id.nav5).forEachIndexed { index, id ->
            content.findViewById<View>(id).setOnClickListener {
                if (!isDismissing) {
                    onNavigate(index)
                    dismiss()
                }
            }
        }
    }

    override fun show() {
        val alreadyShowing = isShowing
        dismissed = false
        panel.translationX = if (ValueAnimator.areAnimatorsEnabled() && !alreadyShowing) {
            if (opensFromRight) menuWidth.toFloat() else -menuWidth.toFloat()
        } else 0f
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        host.requestApplyInsets()
        if (!alreadyShowing && ValueAnimator.areAnimatorsEnabled()) {
            panel.animate().translationX(0f).setDuration(280L).start()
        }
    }

    override fun dismiss() {
        if (isDismissing) return
        if (!isShowing || !panel.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            dismissImmediately()
            return
        }
        isDismissing = true
        panel.animate().cancel()
        panel.animate()
            .translationX(if (opensFromRight) panel.width.toFloat() else -panel.width.toFloat())
            .setDuration(320L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismissImmediately() }
            .start()
    }

    fun dismissImmediately() {
        if (dismissed) return
        dismissed = true
        panel.animate().withEndAction(null).cancel()
        if (ownerActivity?.isDestroyed != true && window?.decorView?.isAttachedToWindow == true) {
            super.dismiss()
        }
        isDismissing = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
