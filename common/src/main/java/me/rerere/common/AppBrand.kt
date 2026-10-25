package me.rerere.common

/** 本分支的对外名称和项目入口，供应用及网络、登录模块共用。 */
object AppBrand {
    const val NAME = "RikkaPlus"
    const val REPOSITORY = "Hoixg/rikkaplus"
    const val PROJECT_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$PROJECT_URL/releases"
    const val UPDATE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val SOURCE_URL = "$PROJECT_URL/tree/1.0"
    const val LICENSE_URL = "$PROJECT_URL/blob/1.0/LICENSE"
    const val DOCUMENTATION_URL = "$PROJECT_URL/blob/1.0/README.md"
    const val DOCUMENTATION_ZH_URL = "$PROJECT_URL/blob/1.0/README_ZH_CN.md"

    fun defaultUserAgent(version: String): String = "$NAME-Android/$version"
}
