package com.glass.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.GlassApp
import com.glass.launcher.data.WidgetKind
import com.glass.launcher.data.WidgetSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The page left of the first home screen: date header + a stack of widgets. */
@Composable
fun TodayView(onSearch: () -> Unit, onRequestPermission: (String) -> Unit) {
    val bd = wallpaperBackdrop()
    val now = rememberNow(60_000)
    val media = GlassApp.instance.media.info
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).glass(Ios.capsule, bd, tint = Color.White.copy(alpha = 0.12f), refraction = 0.5f)
                        .pressable(onSearch).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(19.dp))
                    Text("Search", style = Ios.body.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(start = 8.dp))
                }
                Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)), style = Ios.largeTitle, modifier = Modifier.padding(top = 22.dp))
                Text(SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(now)), style = Ios.title1.copy(color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp))
            }
        }
        item { WidgetView(WidgetKind.Weather, WidgetSize.Medium, Modifier.fillMaxWidth().aspectRatio(2.12f), onRequestPermission) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WidgetView(WidgetKind.Calendar, WidgetSize.Small, Modifier.weight(1f).aspectRatio(1f), onRequestPermission)
                WidgetView(WidgetKind.Battery, WidgetSize.Small, Modifier.weight(1f).aspectRatio(1f))
            }
        }
        item { WidgetView(WidgetKind.Suggestions, WidgetSize.Medium, Modifier.fillMaxWidth().aspectRatio(2.12f)) }
        if (media != null) item { WidgetView(WidgetKind.Music, WidgetSize.Medium, Modifier.fillMaxWidth().aspectRatio(2.12f)) }
        item { WidgetView(WidgetKind.Clock, WidgetSize.Medium, Modifier.fillMaxWidth().aspectRatio(2.12f)) }
        item { WidgetView(WidgetKind.Calendar, WidgetSize.Medium, Modifier.fillMaxWidth().aspectRatio(2.12f), onRequestPermission) }
    }
}
