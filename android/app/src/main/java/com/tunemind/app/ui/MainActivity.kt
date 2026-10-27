package com.tunemind.app.ui

import com.tunemind.app.MainActivity

/**
 * Legacy entrypoint that redirects directly into the Compose MainActivity.
 * Ensures that if the Android OS launcher or an existing icon launches
 * com.tunemind.app.ui.MainActivity, the user sees the modern Jetpack Compose UI.
 */
class MainActivity : MainActivity()
