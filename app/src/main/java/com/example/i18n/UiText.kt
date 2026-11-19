package com.example.i18n

/** Display-only import, connection and subscription-management messages. */
object UiText {
    private val fa = mapOf(
        "Subscription updated" to "اشتراک به‌روزرسانی شد",
        "Subscription saved" to "اشتراک ذخیره شد",
        "Subscription deleted" to "اشتراک حذف شد",
        "Could not update subscription" to "به‌روزرسانی اشتراک ممکن نشد",
        "Could not save subscription" to "ذخیرهٔ اشتراک ممکن نشد",
        "Could not delete subscription" to "حذف اشتراک ممکن نشد",
        "Disconnect before managing subscriptions" to "پیش از مدیریت اشتراک‌ها، اتصال را قطع کنید",
        "Server selection cancelled" to "انتخاب سرور لغو شد",
        "Import a supported server first" to "ابتدا یک پیکربندی پشتیبانی‌شده اضافه کنید",
        "No servers responded. Check your network and try again" to "سروری پاسخ نداد؛ اتصال اینترنت را بررسی و دوباره تلاش کنید",
        "Server list changed. Try again" to "فهرست سرورها تغییر کرد؛ دوباره تلاش کنید",
        "Could not select a server. Try again" to "انتخاب سرور ممکن نشد؛ دوباره تلاش کنید",
        "No servers available for the selected country" to "سروری برای کشور انتخاب‌شده در دسترس نیست",
        "Selected location changed. Try again" to "موقعیت انتخاب‌شده تغییر کرد؛ دوباره تلاش کنید",
        "Wait for the current operation to finish" to "منتظر پایان عملیات فعلی بمانید",
        "Disconnect before importing" to "پیش از افزودن پیکربندی، اتصال را قطع کنید",
        "Nothing imported" to "پیکربندی اضافه نشد",
        "Nothing to import" to "پیکربندی برای افزودن وجود ندارد",
        "Ignored unsafe or app-managed configuration directives" to "دستورهای ناامن یا تحت مدیریت برنامه نادیده گرفته شدند",
        "Invalid HTTP(S) subscription URL" to "نشانی لینک اشتراک معتبر نیست",
        "Invalid subscription URL" to "نشانی لینک اشتراک معتبر نیست",
        "Subscription URL must use HTTP or HTTPS" to "لینک اشتراک باید از نوع HTTP یا HTTPS باشد",
        "No supported stable Xray servers found; Clash and sing-box are not supported" to "پیکربندی پایدار Xray پیدا نشد؛ Clash و sing-box پشتیبانی نمی‌شوند",
        "Unsupported experimental servers were rejected" to "پیکربندی‌های آزمایشی یا پشتیبانی‌نشده پذیرفته نشدند",
        "Subscription contains no supported stable Xray servers" to "اشتراک، پیکربندی پایدار Xray ندارد",
        "Subscription request failed. Check your network and URL" to "دریافت اشتراک ناموفق بود؛ اینترنت و لینک را بررسی کنید",
        "Could not read subscription response" to "پاسخ اشتراک قابل خواندن نیست",
        "Imported configuration could not be saved" to "ذخیرهٔ پیکربندی ممکن نشد",
        "Could not read the imported file" to "فایل پیکربندی قابل خواندن نیست",
        "Could not read or save the imported configuration" to "خواندن یا ذخیرهٔ پیکربندی ممکن نشد",
        "Malformed or unsupported stable Xray outbound" to "خروجی Xray نامعتبر یا پشتیبانی‌نشده است",
        "Malformed Xray server entry" to "اطلاعات سرور Xray معتبر نیست",
        "Malformed Xray user entry" to "اطلاعات کاربر Xray معتبر نیست",
        "Expected an Xray configuration with outbounds; Clash and sing-box are not supported" to "پیکربندی Xray با خروجی معتبر لازم است؛ Clash و sing-box پشتیبانی نمی‌شوند",
        "This server requires unsupported or insecure features" to "این پیکربندی به ویژگی‌های ناامن یا پشتیبانی‌نشده نیاز دارد",
        "This profile is not supported by the stable Xray client" to "این پیکربندی در کلاینت پایدار Xray پشتیبانی نمی‌شود",
        "Core failed to compile the profile" to "آماده‌سازی پیکربندی ممکن نشد",
        "Connection timed out; try again" to "مهلت اتصال تمام شد؛ دوباره تلاش کنید",
        "No supported server is available" to "سرور پشتیبانی‌شده‌ای در دسترس نیست",
        "Unable to start this server" to "راه‌اندازی این سرور ممکن نشد",
        "VPN service stopped" to "سرویس VPN متوقف شد",
        "VPN core stopped" to "هستهٔ VPN متوقف شد",
        "No server passed the secure connection check" to "اتصال اینترنت از طریق هیچ سروری تأیید نشد",
        "Unable to establish the VPN interface" to "ایجاد رابط VPN ممکن نشد",
        "The VPN core could not start this server" to "هستهٔ VPN نتوانست این سرور را راه‌اندازی کند",
        "Import exceeds the 8 MiB size limit" to "حجم پیکربندی بیشتر از ۸ مگابایت است",
        "All connection sources are disabled. Enable at least one source in Subscriptions" to "همهٔ منابع اتصال غیرفعال هستند؛ دست‌کم یک منبع را در اشتراک‌ها فعال کنید",
        "All configurations in enabled sources are disabled" to "همهٔ پیکربندی‌های منابع فعال غیرفعال شده‌اند",
        "No supported configurations in enabled sources" to "پیکربندی پشتیبانی‌شده‌ای در منابع فعال وجود ندارد",
        "Disconnect before deleting the active server" to "پیش از حذف سرور فعال، اتصال را قطع کنید",
        "No duplicate configurations found" to "پیکربندی تکراری یافت نشد",
        "No additional country metadata found" to "اطلاعات کشور بیشتری یافت نشد",
        "Health check cancelled" to "بررسی سلامت لغو شد",
        "Changes apply to the next connection" to "تغییرات در اتصال بعدی اعمال می‌شوند",
        "Network unavailable" to "شبکه در دسترس نیست",
        "DNS resolution failed" to "تفکیک DNS ناموفق بود",
        "Connection timed out" to "مهلت اتصال به پایان رسید",
        "TLS handshake failed" to "توافق امنیتی TLS ناموفق بود",
        "Server authentication failed" to "احراز هویت سرور ناموفق بود",
        "Remote server refused connection" to "سرور مقصد اتصال را رد کرد",
        "Configuration is invalid or unsupported" to "پیکربندی نامعتبر یا پشتیبانی‌نشده است",
        "Exit verification failed" to "تأیید خروجی ناموفق بود",
        "Connection failed" to "اتصال برقرار نشد",
        "Waiting for network to reconnect…" to "در انتظار بازگشت شبکه برای اتصال مجدد…",
        "Backup exported" to "نسخهٔ پشتیبان ذخیره شد",
        "Could not export backup" to "ذخیرهٔ نسخهٔ پشتیبان ممکن نشد",
        "Invalid backup file or wrong passphrase" to "فایل پشتیبان نامعتبر است یا گذرواژه اشتباه است",
        "Disconnect before restoring a backup" to "پیش از بازیابی نسخهٔ پشتیبان، اتصال را قطع کنید"
    )
    private val imported = Regex("^Imported (\\d+) server\\(s\\)$")
    private val importedWithDup = Regex("^Imported (\\d+) server\\(s\\), skipped (\\d+) duplicate\\(s\\)$")
    private val skippedDup = Regex("^Skipped (\\d+) duplicate\\(s\\)$")
    private val deletedCount = Regex("^Deleted (\\d+) configuration\\(s\\)(; active server kept)?$")
    private val restoredCount = Regex("^Restored (\\d+) configuration\\(s\\)$")
    private val removedDupCount = Regex("^Removed (\\d+) duplicate configuration\\(s\\)$")
    private val movedManualCount = Regex("^Moved (\\d+) configuration\\(s\\) to Manual Configurations$")
    private val updatedCountriesCount = Regex("^Updated country estimates for (\\d+) configuration\\(s\\)$")
    private val finishedTestingCount = Regex("^Finished testing (\\d+) configuration\\(s\\)$")
    private val rejected = Regex("^Rejected (\\d+): (.+)$")
    private val movedTrashCount = Regex("^Moved (\\d+) configuration\\(s\\) to Trash(?:; active server kept)?$")
    private val permDeletedCount = Regex("^Permanently deleted (\\d+) configuration\\(s\\)$")
    private val backupRestoredSummary = Regex("^Restored (\\d+) configuration\\(s\\) and (\\d+) subscription\\(s\\)$")
    private val badLink = Regex("^Line (\\d+): malformed or unsupported stable Xray link$")
    private val badOutbound = Regex("^Outbound (\\d+): expected an Xray outbound object$")
    private val badConfig = Regex("^Config (\\d+): expected an Xray object$")
    private val httpError = Regex("^Subscription returned HTTP (\\d+)$")
    private val summary = Regex(
        "^(Imported \\d+ server\\(s\\)(?:, skipped \\d+ duplicate\\(s\\))?|Skipped \\d+ duplicate\\(s\\)|Nothing imported)(?:\\. Rejected (\\d+): (.*?))?" +
            "(\\. Ignored unsafe or app-managed configuration directives)?$"
    )
    fun translate(language: String?, text: String): String {
        if (language != "fa" || text.isEmpty()) return text
        val translated = summary.matchEntire(text)?.let { match ->
            buildString {
                append(segment(match.groupValues[1]))
                if (match.groupValues[2].isNotEmpty()) {
                    append("؛ ${match.groupValues[2]} مورد پذیرفته نشد: ${segment(match.groupValues[3])}")
                }
                if (match.groupValues[4].isNotEmpty()) {
                    append("؛ ")
                    append(fa.getValue("Ignored unsafe or app-managed configuration directives"))
                }
            }
        } ?: segment(text)
        return Digits.localize(translated, language)
    }

    private fun segment(text: String): String {
        fa[text]?.let { return it }
        imported.matchEntire(text)?.let { return "${it.groupValues[1]} سرور اضافه شد" }
        importedWithDup.matchEntire(text)?.let { return "${it.groupValues[1]} سرور اضافه شد و ${it.groupValues[2]} مورد تکراری نادیده گرفته شد" }
        skippedDup.matchEntire(text)?.let { return "${it.groupValues[1]} مورد تکراری نادیده گرفته شد" }
        deletedCount.matchEntire(text)?.let {
            return if (it.groupValues[2].isNotEmpty()) "${it.groupValues[1]} پیکربندی حذف شد؛ سرور فعال نگه داشته شد"
            else "${it.groupValues[1]} پیکربندی حذف شد"
        }
        restoredCount.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی بازیابی شد" }
        removedDupCount.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی تکراری حذف شد" }
        movedManualCount.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی به پیکربندی‌های دستی منتقل شد" }
        updatedCountriesCount.matchEntire(text)?.let { return "کشور تخمینی برای ${it.groupValues[1]} پیکربندی به‌روزرسانی شد" }
        finishedTestingCount.matchEntire(text)?.let { return "آزمایش ${it.groupValues[1]} پیکربندی پایان یافت" }
        movedTrashCount.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی به سطل زباله منتقل شد" }
        permDeletedCount.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی به‌طور دائمی حذف شد" }
        backupRestoredSummary.matchEntire(text)?.let { return "${it.groupValues[1]} پیکربندی و ${it.groupValues[2]} اشتراک بازیابی شد" }
        rejected.matchEntire(text)?.let { return "${it.groupValues[1]} مورد پذیرفته نشد: ${segment(it.groupValues[2])}" }
        badLink.matchEntire(text)?.let { return "خط ${it.groupValues[1]}: لینک Xray نامعتبر یا پشتیبانی‌نشده است" }
        badOutbound.matchEntire(text)?.let { return "خروجی ${it.groupValues[1]}: ساختار خروجی Xray معتبر نیست" }
        badConfig.matchEntire(text)?.let { return "پیکربندی ${it.groupValues[1]}: ساختار Xray معتبر نیست" }
        httpError.matchEntire(text)?.let { return "خطای HTTP ${it.groupValues[1]} در دریافت اشتراک" }
        return text
    }
}
