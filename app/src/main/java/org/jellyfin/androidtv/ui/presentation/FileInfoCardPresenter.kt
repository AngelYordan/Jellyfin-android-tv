package org.jellyfin.androidtv.ui.presentation

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import kotlin.math.roundToInt

class FileInfoCardPresenter : Presenter() {
	class ViewHolder(
		private val container: LinearLayout,
	) : Presenter.ViewHolder(container) {
		fun setText(content: String) {
			container.removeAllViews()

			content.split("\n\n")
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.forEachIndexed { index, block ->
					if (index > 0) container.addSpacer(8)
					container.addBlock(block)
				}
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val context = parent.context
		val view = LinearLayout(context).apply {
			layoutParams = ViewGroup.LayoutParams(context.dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
			minimumHeight = context.dp(120)
			orientation = LinearLayout.VERTICAL
			setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
			setBackgroundResource(R.drawable.file_info_panel_background)
			gravity = Gravity.START
			isFocusable = true
			isFocusableInTouchMode = true
			stateListAnimator = null
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
			setOnFocusChangeListener { focusedView, _ ->
				focusedView.scaleX = 1f
				focusedView.scaleY = 1f
			}
		}

		return ViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return
		viewHolder.setText(item?.toString().orEmpty())
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
}

private fun LinearLayout.addBlock(block: String) {
	val lines = block.lines().filter { it.isNotBlank() }
	if (lines.isEmpty()) return

	val firstLine = lines.first()
	when {
		lines.size == 1 && firstLine.contains(":") -> addKeyValueText(firstLine)
		firstLine.endsWith(":") -> {
			addSectionTitle(firstLine.removeSuffix(":"))
			lines.drop(1).forEach { addSecondaryText(it) }
		}
		firstLine.contains(":") -> {
			val label = firstLine.substringBefore(":")
			val value = firstLine.substringAfter(":").trim()
			addSectionTitle(label)
			if (value.isNotBlank()) addSecondaryText(value)
			lines.drop(1).forEach { addSecondaryText(it) }
		}
		else -> lines.forEach { addSecondaryText(it) }
	}
}

private fun LinearLayout.addKeyValueText(line: String) {
	addView(TextView(context).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		setTextColor(ContextCompat.getColor(context, R.color.button_default_normal_text))
		textSize = 13f
		includeFontPadding = true
		text = line.asBoldLabel()
	})
}

private fun LinearLayout.addSectionTitle(title: String) {
	addView(TextView(context).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		setTextColor(ContextCompat.getColor(context, R.color.white))
		textSize = 12f
		typeface = Typeface.DEFAULT_BOLD
		includeFontPadding = false
		text = title.uppercase()
	})
}

private fun LinearLayout.addSecondaryText(textValue: String) {
	addView(TextView(context).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		setTextColor(ContextCompat.getColor(context, R.color.button_default_normal_text))
		textSize = 13f
		includeFontPadding = true
		text = textValue.asBoldLabel()
	})
}

private fun LinearLayout.addSpacer(height: Int) {
	addView(View(context).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(height))
	})
}

private fun String.asBoldLabel(): CharSequence {
	val delimiterIndex = indexOf(':')
	if (delimiterIndex <= 0) return this

	return SpannableString(this).apply {
		setSpan(StyleSpan(Typeface.BOLD), 0, delimiterIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
	}
}

private fun android.content.Context.dp(value: Int): Int {
	return (value * resources.displayMetrics.density).roundToInt()
}
