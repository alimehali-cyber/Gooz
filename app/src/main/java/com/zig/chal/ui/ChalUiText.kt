package com.zig.chal.ui

import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.render.ChalCompatibilityReason

internal fun ChalRayTracingQuality.uiLabel(persian: Boolean, native: Boolean = false): String = when (this) {
    ChalRayTracingQuality.OFF, ChalRayTracingQuality.LOW -> if (persian) { if (native) "کم" else "ساده" } else { if (native) "Low" else "Preview" }
    ChalRayTracingQuality.MEDIUM -> if (persian) "متوسط" else "Medium"
    ChalRayTracingQuality.HIGH -> if (persian) "زیاد" else "High"
    ChalRayTracingQuality.ULTRA -> if (persian) "بسیار زیاد" else "Ultra"
}

internal fun ChalPresetName.uiLabel(persian: Boolean): String = when (this) {
    ChalPresetName.MAXIMUM_PERFORMANCE -> if (persian) "ساده و سریع" else "Fast preview"
    ChalPresetName.BALANCED -> if (persian) "متعادل" else "Balanced"
    ChalPresetName.HIGH_QUALITY -> if (persian) "جزئیات بیشتر" else "Detailed"
    ChalPresetName.ULTRA_QUALITY -> if (persian) "بیشترین جزئیات" else "Ultra"
    ChalPresetName.CUSTOM -> if (persian) "سفارشی" else "Custom"
}

internal fun ChalCompatibilityReason.explanation(persian: Boolean): String = if (persian) when (this) {
    ChalCompatibilityReason.ANDROID_VERSION -> "رندر بومی به اندروید ۱۰ یا جدیدتر نیاز دارد؛ اکنون حالت سازگار OpenGL فعال است."
    ChalCompatibilityReason.ABI -> "رندر بومی فقط برای پردازنده‌های ARM64 است؛ اکنون حالت سازگار OpenGL فعال است."
    ChalCompatibilityReason.VULKAN_VERSION -> "این دستگاه پشتیبانی از Vulkan 1.1 را اعلام نمی‌کند؛ اکنون حالت سازگار OpenGL فعال است."
    ChalCompatibilityReason.LIBRARY_LOAD -> "کتابخانهٔ رندر بومی بارگذاری نشد؛ اکنون حالت سازگار OpenGL فعال است."
    ChalCompatibilityReason.NATIVE_RUNTIME -> "رندر بومی با خطا روبه‌رو شد. تنظیمات حفظ شده و رندر سازگار OpenGL جایگزین آن شده است."
} else when (this) {
    ChalCompatibilityReason.ANDROID_VERSION -> "Native rendering requires Android 10 or later. OpenGL compatibility mode is active."
    ChalCompatibilityReason.ABI -> "Native rendering requires an ARM64 processor. OpenGL compatibility mode is active."
    ChalCompatibilityReason.VULKAN_VERSION -> "This device does not report Vulkan 1.1 support. OpenGL compatibility mode is active."
    ChalCompatibilityReason.LIBRARY_LOAD -> "The native libraries could not be loaded. OpenGL compatibility mode is active."
    ChalCompatibilityReason.NATIVE_RUNTIME -> "Native rendering failed. Your settings were retained and OpenGL compatibility mode has taken over."
}

internal fun modelDisclosure(persian: Boolean, native: Boolean): String = if (persian) {
    if (native) "تصویر با موتور واردشدهٔ Vulkan تولید می‌شود. مقادیر کر از توابع تحلیلی موتور می‌آیند؛ دقت فیزیکی تصویر این موتور به‌طور مستقل تأیید نشده است."
    else "این رندر OpenGL یک نمایش بصری تخمینی است، نه حل کامل ژئودزیک‌های کر. نرخ ساعت و انتقال به سرخ ناظر، برآوردهای شوارتزشیلد هستند و اثر چرخش را شامل نمی‌شوند."
} else {
    if (native) "Rendered by the imported Vulkan engine. Kerr readouts use its analytic helpers; the engine's rendered physics have not been independently validated."
    else "This OpenGL visualization is approximate, not a complete Kerr geodesic solution. Observer clock rate and redshift are Schwarzschild estimates and do not include rotation."
}
