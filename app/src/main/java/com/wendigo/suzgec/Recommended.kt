package com.wendigo.suzgec

object Recommended {

    data class Item(val label: String, val url: String)

    val LISTS = listOf(
        Item(
            "AdGuard DNS",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        ),
        Item(
            "OISD Small",
            "https://small.oisd.nl/domainswild"
        ),
        Item(
            "Peter Lowe",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"
        ),
        Item(
            "CNAME izleyiciler",
            "https://raw.githubusercontent.com/AdguardTeam/cname-trackers/master/data/combined_disguised_trackers_justdomains.txt"
        ),
        Item(
            "EasyList",
            "https://easylist.to/easylist/easylist.txt"
        ),
        Item(
            "EasyPrivacy",
            "https://easylist.to/easylist/easyprivacy.txt"
        ),
        Item(
            "StevenBlack",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        )
    )
}
