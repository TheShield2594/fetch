package com.fetch.tv.core.di

import com.fetch.tv.data.local.ProfileJson
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.data.remote.api.AniSkipApi
import com.fetch.tv.data.remote.api.AnimeSkipApi
import com.fetch.tv.data.remote.api.AuthDiagnosticReportApi
import com.fetch.tv.data.remote.api.GitHubReleaseApi
import com.fetch.tv.data.remote.api.ImdbTapframeApi
import com.fetch.tv.data.remote.api.IntroDbApi
import com.fetch.tv.data.remote.api.MDBListApi
import com.fetch.tv.data.remote.api.ParentalGuideApi
import com.fetch.tv.data.remote.api.PlaybackIssueReportApi
import com.fetch.tv.data.remote.api.SeriesGraphApi
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TraktApi
import com.fetch.tv.data.remote.api.TrailerApi
import com.fetch.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.fetch.tv.data.remote.dto.mdblist.MDBListMediaRequestDto
import com.fetch.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.squareup.moshi.Types
import okhttp3.ResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation

/**
 * The app's Moshi has no reflection fallback, so every Kotlin type it reads or writes needs a
 * generated adapter (`@JsonClass(generateAdapter = true)`). Building an adapter builds the
 * adapters of every property type too, so asking for each API type covers the whole tree.
 */
class MoshiCodegenCoverageTest {

    private val moshi = NetworkModule.provideMoshi()

    private val apis = listOf(
        AddonApi::class.java,
        AniSkipApi::class.java,
        AnimeSkipApi::class.java,
        AuthDiagnosticReportApi::class.java,
        GitHubReleaseApi::class.java,
        ImdbTapframeApi::class.java,
        IntroDbApi::class.java,
        MDBListApi::class.java,
        ParentalGuideApi::class.java,
        PlaybackIssueReportApi::class.java,
        SeriesGraphApi::class.java,
        TmdbApi::class.java,
        TraktApi::class.java,
        TrailerApi::class.java,
    )

    /** Types handed to `moshi.adapter` directly rather than through Retrofit. */
    private val directTypes: List<Type> = listOf(
        AuthDiagnosticReportRequestDto::class.java,
        MDBListMediaRequestDto::class.java,
        MDBListMediaResponseDto::class.java,
        Types.newParameterizedType(List::class.java, ProfileJson::class.java),
    )

    @Test
    fun everyJsonTypeHasAnAdapterWithoutReflection() {
        val types = apis.flatMap { api -> api.declaredMethods.flatMap(::jsonTypes) } + directTypes
        val failures = types.distinct().mapNotNull { type ->
            runCatching { moshi.adapter<Any>(type) }.exceptionOrNull()?.let { "$type: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun jsonTypes(method: java.lang.reflect.Method): List<Type> {
        val params = method.genericParameterTypes
        val bodies = params.indices
            .filter { i -> method.parameterAnnotations[i].any { it is Body } }
            .map { params[it] }
        // A suspend function's result type is the argument of its trailing Continuation.
        val continuation = params.lastOrNull() as? ParameterizedType
        val result = if (continuation?.rawType == Continuation::class.java) {
            lowerBound(continuation.actualTypeArguments[0])
        } else {
            method.genericReturnType
        }
        return (bodies + unwrap(result)).filterNot { it == ResponseBody::class.java || it == Unit::class.java }
    }

    private fun unwrap(type: Type): Type =
        if (type is ParameterizedType && (type.rawType == Response::class.java || type.rawType == Call::class.java)) {
            unwrap(type.actualTypeArguments[0])
        } else {
            type
        }

    private fun lowerBound(type: Type): Type =
        if (type is WildcardType) type.lowerBounds.firstOrNull() ?: type.upperBounds[0] else type
}
