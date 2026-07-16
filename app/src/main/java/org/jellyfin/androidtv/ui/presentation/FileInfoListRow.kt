package org.jellyfin.androidtv.ui.presentation

import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter

class FileInfoListRow(
	headerItem: HeaderItem,
	adapter: ObjectAdapter,
) : ListRow(headerItem, adapter)
