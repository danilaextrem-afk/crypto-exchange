package app.freevpn.core

/**
 * Список известных российских приложений для режима «напрямую».
 * ВАЖНО: совместимость НЕ проверена на реальных устройствах. Имена пакетов могут
 * меняться — список нужно сверять с установленными версиями приложений.
 */
object RuApps {
    val packages: Map<String, String> = linkedMapOf(
        "ru.sberbankmobile" to "СберБанк Онлайн",
        "com.idamob.tinkoff.android" to "Т-Банк",
        "ru.vtb24.mobilebanking.android" to "ВТБ",
        "ru.alfabank.mobile.android" to "Альфа-Банк",
        "ru.rostel" to "Госуслуги",
        "ru.yandex.searchplugin" to "Яндекс",
        "ru.yandex.yandexmaps" to "Яндекс Карты",
        "ru.yandex.yandexnavi" to "Яндекс Навигатор",
        "ru.yandex.music" to "Яндекс Музыка",
        "ru.yandex.taxi" to "Яндекс Go",
        "ru.yandex.market" to "Яндекс Маркет",
        "com.vkontakte.android" to "VK",
        "com.vk.vkvideo" to "VK Видео",
        "ru.ok.android" to "Одноклассники",
        "ru.mail.mailapp" to "Почта Mail",
        "ru.yandex.mail" to "Яндекс Почта",
        "ru.kinopoisk" to "Кинопоиск",
        "ru.dublgis.dgismobile" to "2ГИС",
        "ru.vk.store" to "RuStore",
        "ru.mts.mymts" to "Мой МТС",
        "ru.megafon.mlk" to "МегаФон",
        "ru.beeline.services" to "Мой Билайн",
        "ru.tele2.mytele2" to "t2 (Tele2)",
        "ru.ozon.app.android" to "Ozon",
        "com.wildberries.ru" to "Wildberries",
        "com.avito.android" to "Авито",
        "ru.nspk.mirpay" to "Mir Pay",
        "ru.sberbank.sberpay" to "СберПэй"
    )
    // Telegram намеренно НЕ в списке: это не российский сервис, а звонки в нём в России
    // ограничены с августа 2025 г. — в обход VPN они бы как раз не работали.
}

object RoutingPlanner {
    /**
     * Строит план Split Tunneling.
     *
     * Гарантии (проверяются тестами):
     *  - собственное приложение всегда идёт через VPN (иначе не работают проверки туннеля);
     *  - include и exclude никогда не заполнены одновременно;
     *  - в списки попадают только установленные приложения (иначе VpnService.Builder бросает исключение);
     *  - режим MANUAL с пустым выбором означает «через VPN идёт только само приложение».
     */
    fun plan(
        mode: RoutingMode,
        installed: Set<String>,
        vpnApps: Set<String>,
        directApps: Set<String>,
        ownPackage: String
    ): SplitPlan = when (mode) {
        RoutingMode.ALL -> SplitPlan.NONE
        RoutingMode.RU_DIRECT -> {
            val direct = (RuApps.packages.keys + directApps)
                .filter { it in installed && it != ownPackage }
                .toSet()
            SplitPlan(emptySet(), direct)
        }
        RoutingMode.MANUAL -> {
            val include = vpnApps.filter { it in installed }.toMutableSet()
            include += ownPackage
            SplitPlan(include, emptySet())
        }
    }
}
