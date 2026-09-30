package mihon.domain.content.model

import kotlinx.serialization.Serializable

@Serializable
enum class ContentRatingOverride(val code: Int) {
    AUTO(0),
    NORMAL(1),
    ADULT(2),
    ;

    companion object {
        fun fromCode(code: Int): ContentRatingOverride = entries.firstOrNull { it.code == code } ?: AUTO
    }
}
