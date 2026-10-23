package com.aistra.hails.extensions

import androidx.fragment.app.Fragment

val Fragment.isLandscape get() = requireContext().isLandscape
val Fragment.isRtl get() = requireContext().isRtl