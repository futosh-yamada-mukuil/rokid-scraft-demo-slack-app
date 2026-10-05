package com.scraft.glassreport.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.scraft.glassreport.SCRAFTApp

/** Composable から DI コンテナを取得する。 */
@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as SCRAFTApp).container
