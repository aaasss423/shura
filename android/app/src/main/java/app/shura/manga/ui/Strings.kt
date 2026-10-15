package app.shura.manga.ui

import androidx.compose.runtime.Composable
import app.shura.manga.ui.theme.UiLanguage

/**
 * Every user-visible string, in both languages, in one place.
 *
 * A map of key to a per-language string rather than an Android `strings.xml`
 * pair, because the two languages are not a build-time variant here: the user
 * switches between them at runtime and the choice persists. Nothing in the UI
 * hardcodes English, which is what makes Arabic (and RTL) a real case rather
 * than a translation pass nobody re-checks.
 *
 * A key with no Arabic entry falls back to English, and a key present in no
 * language renders as the key itself - visible, rather than silently blank.
 */
object Strings {
    private val ar = mapOf(
        "app.name" to "شورا",
        "nav.home" to "الرئيسية",
        "nav.discover" to "اكتشف",
        "nav.library" to "المكتبة",
        "nav.settings" to "الإعدادات",
        "nav.reader" to "القارئ",
        "home.continue" to "متابعة القراءة",
        "home.recent" to "آخر القراءات",
        "home.empty" to "لا شيء بعد. ابدأ من تبويب اكتشف.",
        "home.emptyHint" to "اختر مصدرًا، ابحث عن عمل، ثم افتح فصلًا.",
        "discover.sources" to "المصادر",
        "discover.search" to "ابحث عن عمل",
        "discover.noResults" to "لا نتائج",
        "discover.searching" to "جارٍ البحث…",
        "discover.chapters" to "الفصول",
        "discover.noChapters" to "لا فصول",
        "discover.sourcesEmpty" to "لا مصادر. ثبّت مستودعًا من الإعدادات.",
        "common.back" to "رجوع",
        "common.retry" to "إعادة المحاولة",
        "common.cancel" to "إلغاء",
        "common.close" to "إغلاق",
        "common.loading" to "جارٍ التحميل…",
        "common.error" to "خطأ",
        "reader.pages" to "صفحة %1\$d من %2\$d",
        "reader.prev" to "الفصل السابق",
        "reader.next" to "الفصل التالي",
        "reader.noPages" to "لا توجد صفحات في هذا الفصل.",
        "reader.translate" to "ترجمة",
        "reader.translationOff" to "إيقاف",
        "reader.translationFull" to "الفصل كاملًا",
        "reader.translationFollow" to "أثناء القراءة",
        "reader.translationUnavailable" to "محرك الترجمة غير متصل",
        "reader.translationPending" to "بانتظار المحرك",
        "reader.translationCached" to "مترجم",
        "library.title" to "مكتبة الفصل",
        "library.empty" to "لا فصول محفوظة",
        "library.delete" to "حذف",
        "library.read" to "قراءة",
        "library.pages" to "%d صفحة",
        "library.size" to "%s على الجهاز",
        "library.openFolder" to "إظهار الملفات",
        "settings.language" to "اللغة",
        "settings.theme" to "المظهر",
        "settings.dark" to "داكن",
        "settings.light" to "فاتح",
        "settings.repository" to "المستودع",
        "settings.installRepository" to "تثبيت مستودع من التخزين",
        "settings.noRepository" to "لا يوجد مستودع مثبت.",
        "store.title" to "ال إضافات",
        "store.trusted" to "موثوق",
        "store.unknownPublisher" to "ناشر غير موثوق",
        "store.outOfScope" to "خارج النطاق",
        "store.notDownloaded" to "غير موجود على الجهاز",
        "store.runnable" to "%d من %d إضافة تعمل في هذه النسخة",
        "store.open" to "إدارة الإضافات",
        "store.empty" to "لا يقدّم هذا المستودع أي إضافات.",
        "store.remove" to "إزالة",
        "store.install" to "تثبيت",
        "store.update" to "تحديث",
        "store.installed" to "مثبّتة: %s",
        "store.repos" to "المستودع",
        "store.removed" to "تمت إزالة %s. أُزيلت مصادره من التطبيق.",
        "reader.mode.vertical" to "شريط",
        "reader.mode.paged" to "صفحات",
        "error.generic" to "تعذّر إكمال العملية.",
    )

    private val en = mapOf(
        "app.name" to "Shura",
        "nav.home" to "Home",
        "nav.discover" to "Discover",
        "nav.library" to "Library",
        "nav.settings" to "Settings",
        "nav.reader" to "Reader",
        "home.continue" to "Continue reading",
        "home.recent" to "Recently read",
        "home.empty" to "Nothing yet. Start from Discover.",
        "home.emptyHint" to "Pick a source, search for a title, then open a chapter.",
        "discover.sources" to "Sources",
        "discover.search" to "Search for a title",
        "discover.noResults" to "No results",
        "discover.searching" to "Searching…",
        "discover.chapters" to "Chapters",
        "discover.noChapters" to "No chapters",
        "discover.sourcesEmpty" to "No sources. Install a repository in Settings.",
        "common.back" to "Back",
        "common.retry" to "Retry",
        "common.cancel" to "Cancel",
        "common.close" to "Close",
        "common.loading" to "Loading…",
        "common.error" to "Error",
        "reader.pages" to "Page %1\$d of %2\$d",
        "reader.prev" to "Previous chapter",
        "reader.next" to "Next chapter",
        "reader.noPages" to "This chapter has no pages.",
        "reader.translate" to "Translate",
        "reader.translationOff" to "Off",
        "reader.translationFull" to "Full chapter",
        "reader.translationFollow" to "Follow reading",
        "reader.translationUnavailable" to "Translation engine offline",
        "reader.translationPending" to "Waiting for engine",
        "reader.translationCached" to "Translated",
        "library.title" to "Chapter library",
        "library.empty" to "No saved chapters",
        "library.delete" to "Delete",
        "library.read" to "Read",
        "library.pages" to "%d pages",
        "library.size" to "%s on device",
        "library.openFolder" to "Show files",
        "settings.language" to "Language",
        "settings.theme" to "Appearance",
        "settings.dark" to "Dark",
        "settings.light" to "Light",
        "settings.repository" to "Repository",
        "settings.installRepository" to "Install repository from storage",
        "settings.noRepository" to "No repository installed.",
        "store.title" to "Extensions",
        "store.trusted" to "Trusted",
        "store.unknownPublisher" to "Untrusted publisher",
        "store.outOfScope" to "Out of scope",
        "store.notDownloaded" to "Not on device",
        "store.runnable" to "%d of %d extensions can run in this build",
        "store.open" to "Manage extensions",
        "store.empty" to "This repository offers no extensions.",
        "store.remove" to "Remove",
        "store.install" to "Install",
        "store.update" to "Update",
        "store.installed" to "Installed: %s",
        "store.repos" to "Repository",
        "store.removed" to "Removed %s. Its sources are gone from the app.",
        "reader.mode.vertical" to "Strip",
        "reader.mode.paged" to "Paged",
        "error.generic" to "That did not work.",
    )

    private fun table(language: UiLanguage) = if (language == UiLanguage.ARABIC) ar else en

    fun t(key: String, language: UiLanguage, vararg args: Any): String {
        val text = table(language)[key] ?: en[key] ?: key
        return if (args.isEmpty()) text else String.format(text, *args)
    }
}

/** Formats with the active language, for use inside a composable. */
@Composable
fun shura(key: String, vararg args: Any): String =
    Strings.t(key, app.shura.manga.ui.theme.LocalUiLanguage.current, *args)
