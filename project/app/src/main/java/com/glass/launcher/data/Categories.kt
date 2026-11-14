package com.glass.launcher.data

import android.content.pm.ApplicationInfo

enum class AppCategory(val title: String) {
    Social("Social"),
    Entertainment("Entertainment"),
    Shopping("Shopping & Food"),
    Finance("Finance"),
    Travel("Travel"),
    Productivity("Productivity & Finance"),
    Creativity("Creativity"),
    Education("Education"),
    Health("Health & Fitness"),
    Games("Games"),
    Information("Information & Reading"),
    Utilities("Utilities"),
    Other("Other");

    companion object {
        private val rules: List<Pair<AppCategory, List<String>>> = listOf(
            Social to listOf("whatsapp", "instagram", "facebook.katana", "twitter", "linkedin", "reddit", "telegram", "truecaller", "snapchat", "messaging", "mms", "contacts", "dialer", "incallui", "tachyon", "discord", "signal", "samvaad", "slack"),
            Entertainment to listOf("hotstar", "youtube", "netflix", "primevideo", "mxtech", "netmirror", "bms", "spotify", "music", "jiocinema", "gaana", "player", "videos", "chromecast"),
            Shopping to listOf("amazon.mshop", "meesho", "swiggy", "zomato", "zepto", "grofers", "blinkit", "lenskart", "flipkart", "myntra", "dominos", "countrydelight", "ownly", "plazza", "swish", "bigbasket", "ajio", "nykaa"),
            Finance to listOf("groww", "paisa", "sbi", "bajaj", "zerodha.kite", "phonepe", "paytm", "cred", "hdfc", "icici", "axis", "upi", "bank", "wallet", "payment"),
            Travel to listOf("makemytrip", "cleartrip", "ola", "uber", "rapido", "cris", "whereismytrain", "digiyatra", "indianoil", "maps", "irctc", "redbus", "goibibo", "mindicator", "utsmobile", "aikyam", "playo"),
            Productivity to listOf("docs", "sheets", "slides", "gm", "gmail", "calendar", "notes", "skydrive", "onedrive", "office", "wps", "authenticator", "duosecurity", "drive", "keep", "perplexity", "bard", "gemini", "chatgpt", "claude", "scanner", "docscanner", "files", "fileexplorer", "tailscale", "vpn", "github", "gitstreak", "screensearch", "midrop", "sendanywhere", "adm"),
            Creativity to listOf("camera", "gallery", "photos", "kinemaster", "frontrow", "vlog", "mediaeditor", "screenrecorder", "soundrecorder", "droidcam", "canva", "snapseed", "lightroom", "capcut"),
            Education to listOf("duolingo", "varsity", "learnkannada", "kannada", "bhagwadgita", "readera", "byju", "unacademy", "coursera"),
            Health to listOf("apollo", "homeworkout", "innerhour", "fit", "health", "wellbeing", "cult", "healthify", "pharmeasy"),
            Games to listOf("chess", "game", "pubg", "freefire", "ludo"),
            Information to listOf("weather", "news", "compass", "inshorts", "dailyhunt", "chrome", "browser", "googlequicksearchbox", "digilocker", "umang", "uidai", "facialauth", "facerd", "pehchaan", "jio.myjio", "mi.global"),
            Utilities to listOf("settings", "calculator", "clock", "deskclock", "securitycenter", "updater", "vending", "packageinstaller", "thememanager", "backup", "cleaner", "barcode", "qr", "remote", "android.stk", "glass.launcher"),
        )

        fun of(pkg: String, info: ApplicationInfo?): AppCategory {
            val p = pkg.lowercase()
            rules.firstOrNull { (_, keys) -> keys.any { p.contains(it) } }?.let { return it.first }
            return when (info?.category) {
                ApplicationInfo.CATEGORY_GAME -> Games
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> Entertainment
                ApplicationInfo.CATEGORY_IMAGE -> Creativity
                ApplicationInfo.CATEGORY_SOCIAL -> Social
                ApplicationInfo.CATEGORY_NEWS -> Information
                ApplicationInfo.CATEGORY_MAPS -> Travel
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> Productivity
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> Utilities
                else -> if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) Utilities else Other
            }
        }
    }
}
