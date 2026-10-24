package com.fetch.tv.data.local

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.data.remote.supabase.AvatarCatalogItem

/**
 * Avatars shipped in the APK, so every profile can have a picture without a sync server.
 * Artwork: Noto Emoji (Apache License 2.0), see docs/third-party/avatars/.
 */
object BundledAvatars {
    /** Bundled avatar ids carry this prefix so they never collide with a server catalog's ids. */
    const val ID_PREFIX = "fetch:"

    private data class BundledAvatar(
        val key: String,
        val nameRes: Int,
        val category: String,
        val bgColor: String
    )

    private val avatars = listOf(
        BundledAvatar("fox", R.string.avatar_name_fox, "animals", "#F4511E"),
        BundledAvatar("cat", R.string.avatar_name_cat, "animals", "#8E24AA"),
        BundledAvatar("dog", R.string.avatar_name_dog, "animals", "#8D6E63"),
        BundledAvatar("panda", R.string.avatar_name_panda, "animals", "#43A047"),
        BundledAvatar("koala", R.string.avatar_name_koala, "animals", "#78909C"),
        BundledAvatar("lion", R.string.avatar_name_lion, "animals", "#00897B"),
        BundledAvatar("tiger", R.string.avatar_name_tiger, "animals", "#00838F"),
        BundledAvatar("frog", R.string.avatar_name_frog, "animals", "#00897B"),
        BundledAvatar("owl", R.string.avatar_name_owl, "animals", "#5C6BC0"),
        BundledAvatar("penguin", R.string.avatar_name_penguin, "animals", "#1E88E5"),
        BundledAvatar("unicorn", R.string.avatar_name_unicorn, "animals", "#D81B60"),
        BundledAvatar("dragon", R.string.avatar_name_dragon, "animals", "#C62828"),
        BundledAvatar("octopus", R.string.avatar_name_octopus, "animals", "#8E24AA"),
        BundledAvatar("monkey", R.string.avatar_name_monkey, "animals", "#26A69A"),
        BundledAvatar("rabbit", R.string.avatar_name_rabbit, "animals", "#EC407A"),
        BundledAvatar("bear", R.string.avatar_name_bear, "animals", "#FFB300"),
        BundledAvatar("hamster", R.string.avatar_name_hamster, "animals", "#5C6BC0"),
        BundledAvatar("sloth", R.string.avatar_name_sloth, "animals", "#7CB342"),
        BundledAvatar("dinosaur", R.string.avatar_name_dinosaur, "animals", "#F9A825"),
        BundledAvatar("butterfly", R.string.avatar_name_butterfly, "animals", "#00ACC1"),
        BundledAvatar("robot", R.string.avatar_name_robot, "characters", "#546E7A"),
        BundledAvatar("alien", R.string.avatar_name_alien, "characters", "#7B1FA2"),
        BundledAvatar("ghost", R.string.avatar_name_ghost, "characters", "#5E35B1"),
        BundledAvatar("space_invader", R.string.avatar_name_space_invader, "characters", "#00ACC1"),
        BundledAvatar("cool", R.string.avatar_name_cool, "characters", "#1E88E5"),
        BundledAvatar("pumpkin", R.string.avatar_name_pumpkin, "characters", "#4527A0"),
        BundledAvatar("rocket", R.string.avatar_name_rocket, "fun", "#F4511E"),
        BundledAvatar("star", R.string.avatar_name_star, "fun", "#3949AB"),
        BundledAvatar("pizza", R.string.avatar_name_pizza, "fun", "#00897B"),
        BundledAvatar("popcorn", R.string.avatar_name_popcorn, "fun", "#1E88E5"),
        BundledAvatar("controller", R.string.avatar_name_controller, "fun", "#455A64"),
        BundledAvatar("guitar", R.string.avatar_name_guitar, "fun", "#6A1B9A"),
        BundledAvatar("soccer", R.string.avatar_name_soccer, "fun", "#43A047"),
        BundledAvatar("basketball", R.string.avatar_name_basketball, "fun", "#3949AB"),
        BundledAvatar("rainbow", R.string.avatar_name_rainbow, "fun", "#26A69A"),
        BundledAvatar("cactus", R.string.avatar_name_cactus, "fun", "#689F38"),
        BundledAvatar("sun", R.string.avatar_name_sun, "fun", "#1565C0"),
        BundledAvatar("crystal_ball", R.string.avatar_name_crystal_ball, "fun", "#263238"),
        BundledAvatar("clapper", R.string.avatar_name_clapper, "fun", "#37474F"),
        BundledAvatar("donut", R.string.avatar_name_donut, "fun", "#D81B60")
    )

    private val keys = avatars.map { it.key }.toSet()

    fun catalog(context: Context): List<AvatarCatalogItem> = avatars.mapIndexed { index, avatar ->
        AvatarCatalogItem(
            id = ID_PREFIX + avatar.key,
            displayName = context.getString(avatar.nameRes),
            imageUrl = assetUrl(avatar.key),
            category = avatar.category,
            sortOrder = index,
            bgColor = avatar.bgColor
        )
    }

    /** The image for a bundled [avatarId], or null when it isn't one. */
    fun imageUrl(avatarId: String): String? {
        val key = avatarId.removePrefix(ID_PREFIX).takeIf { avatarId.startsWith(ID_PREFIX) } ?: return null
        return if (key in keys) assetUrl(key) else null
    }

    private fun assetUrl(key: String) = "file:///android_asset/avatars/$key.webp"
}
