package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = CyanNeon500,
    secondary = CyanNeon300,
    tertiary = PurpleNeon,
    background = SlateGray950,
    surface = SlateGray905,
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6)
  )

private val LightColorScheme =
  darkColorScheme( // We enforce our gorgeous dark slate theme by default
    primary = CyanNeon500,
    secondary = CyanNeon300,
    tertiary = PurpleNeon,
    background = SlateGray950,
    surface = SlateGray905,
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force Dark Theme for our direct calling gateway
  dynamicColor: Boolean = false, // Disable dynamic colors to maintain our custom visual vibe
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
