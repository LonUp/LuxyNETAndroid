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
        "Import exceeds the 8 MiB size limit" to "حجم پیکربندی بیشتر از ۸ مگابایت است"
    )
    private val imported = Regex("^Imported (\\d+) server\\(s\\)$")
    private val rejected = Regex("^Rejected (\\d+): (.+)$")
    private val badLink = Regex("^Line (\\d+): malformed or unsupported stable Xray link$")
    private val badOutbound = Regex("^Outbound (\\d+): expected an Xray outbound object$")
    private val badConfig = Regex("^Config (\\d+): expected an Xray object$")
    private val httpError = Regex("^Subscription returned HTTP (\\d+)$")
    private val summary = Regex(
        "^(Imported \\d+ server\\(s\\)|Nothing imported)(?:\\. Rejected (\\d+): (.*?))?" +
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
        rejected.matchEntire(text)?.let { return "${it.groupValues[1]} مورد پذیرفته نشد: ${segment(it.groupValues[2])}" }
        badLink.matchEntire(text)?.let { return "خط ${it.groupValues[1]}: لینک Xray نامعتبر یا پشتیبانی‌نشده است" }
        badOutbound.matchEntire(text)?.let { return "خروجی ${it.groupValues[1]}: ساختار خروجی Xray معتبر نیست" }
        badConfig.matchEntire(text)?.let { return "پیکربندی ${it.groupValues[1]}: ساختار Xray معتبر نیست" }
        httpError.matchEntire(text)?.let { return "خطای HTTP ${it.groupValues[1]} در دریافت اشتراک" }
        return text
    }
}
