package com.aistra.hails.extensions

import android.view.View

val View.isRtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
