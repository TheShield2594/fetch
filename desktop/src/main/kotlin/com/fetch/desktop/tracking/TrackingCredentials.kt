package com.fetch.desktop.tracking

import java.util.Properties

/**
 * The app's Trakt and Simkl API credentials. A blank value means that service is not configured
 * for this build, and its sign-in should be hidden.
 */
data class TrackingCredentials(
    val traktClientId: String,
    val traktClientSecret: String,
    val simklClientId: String,
    val simklAppName: String = DEFAULT_SIMKL_APP_NAME,
) {
    /** Trakt needs both the client id and secret for the device flow and token refresh. */
    val traktAvailable: Boolean get() = traktClientId.isNotBlank() && traktClientSecret.isNotBlank()

    /** Simkl's PIN flow needs only the client id. */
    val simklAvailable: Boolean get() = simklClientId.isNotBlank()

    companion object {
        private const val DEFAULT_SIMKL_APP_NAME = "fetch"

        /**
         * Reads `/fetch-desktop.properties` from the classpath, falling back to the environment
         * variables `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `SIMKL_CLIENT_ID` and `SIMKL_APP_NAME`.
         */
        fun load(): TrackingCredentials {
            val props = Properties()
            runCatching {
                TrackingCredentials::class.java.getResourceAsStream("/fetch-desktop.properties")?.use(props::load)
            }
            return from(props, System::getenv)
        }

        /** [load] with its sources injected, for tests. */
        internal fun from(props: Properties, env: (String) -> String?): TrackingCredentials {
            fun value(key: String, envKey: String): String =
                props.getProperty(key).usable() ?: env(envKey).usable() ?: ""
            return TrackingCredentials(
                traktClientId = value("traktClientId", "TRAKT_CLIENT_ID"),
                traktClientSecret = value("traktClientSecret", "TRAKT_CLIENT_SECRET"),
                simklClientId = value("simklClientId", "SIMKL_CLIENT_ID"),
                simklAppName = value("simklAppName", "SIMKL_APP_NAME").ifBlank { DEFAULT_SIMKL_APP_NAME },
            )
        }

        /** Blank values, and unexpanded `${placeholder}`s from resource filtering, count as missing. */
        private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") }
    }
}
