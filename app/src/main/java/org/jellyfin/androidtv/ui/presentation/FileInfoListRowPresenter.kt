package org.jellyfin.androidtv.ui.presentation

import android.view.View
import androidx.core.view.isVisible
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter

class FileInfoListRowPresenter @JvmOverloads constructor(
	private val topPadding: Int? = null,
) : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE) {
	init {
		headerPresenter = CustomRowHeaderPresenter()
		setSelectEffectEnabled(false)
	}

	override fun isUsingDefaultShadow() = false

	override fun isUsingDefaultListSelectEffect() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun applySelectLevelToChild(holder: ViewHolder, child: View) {
		child.scaleX = 1f
		child.scaleY = 1f
		child.alpha = 1f
	}

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)

		val view = holder.view?.parent as? View ?: return
		if (topPadding != null) view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)

		holder.headerViewHolder.view.isVisible = !(item is ListRow && item.headerItem == null)
	}
}
