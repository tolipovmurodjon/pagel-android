package org.pagel.ai.widget.layout

import androidx.compose.runtime.Composable
import androidx.glance.LocalContext
import org.pagel.ai.R
import androidx.glance.action.Action

/**
 * Content to be displayed when there are no items in the list. To be displayed below the
 * app-specific title bar in the [androidx.glance.appwidget.components.Scaffold] .
 */
@Composable
internal fun EmptyListContent(onOpen: Action) {
  val context = LocalContext.current

  NoDataContent(
    noDataText = context.getString(R.string.widget_today_empty),
    noDataIconRes = R.drawable.sample_no_data_icon,
    actionButtonText = context.getString(R.string.widget_today_open),
    actionButtonIcon = R.drawable.sample_add_icon,
    actionButtonOnClick = onOpen
  )
}
